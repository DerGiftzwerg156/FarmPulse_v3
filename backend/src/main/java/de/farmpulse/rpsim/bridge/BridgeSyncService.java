package de.farmpulse.rpsim.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos.AckDocument;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.InstructionsDocument;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.cycle.CycleDispatcher;
import de.farmpulse.rpsim.cycle.CycleDispatcher.Outcome;
import de.farmpulse.rpsim.cycle.CycleQueue;
import de.farmpulse.rpsim.domain.CycleEvent;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PromptStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.GamePromptRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PlayerResponseRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.CalendarService;
import de.farmpulse.rpsim.time.GameClockService;
import de.farmpulse.rpsim.time.GameClockService.TickWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One bridge cycle: read market_context.json, farm_facts.json, instructions_ack.json and player_responses.json, then
 * write instructions.json from the outbox. savegameId consistency is checked on every file (warning, no crash).
 * <p>
 * Technical review 10/2026, Phase 1.2/1.3 (R-1): there is no transaction around the cycle. Every read stores its data
 * and enqueues the resulting events in one transaction ({@link CycleQueue}); the file is only remembered as read after
 * that commit. The queue is then delivered listener by listener ({@link CycleDispatcher}, game time via
 * {@link GameClockService}). While a savegame still has queued work (a listener failed and is retried), no new file of
 * it is read, so the game logic keeps its order.
 */
@Service
public class BridgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(BridgeSyncService.class);

    private final BridgeFiles files;
    private final SavegameRepository savegames;
    private final FactsSnapshotRepository snapshots;
    private final OutboxInstructionRepository outbox;
    private final OutboxService outboxService;
    private final DetectedSavegameRegistry detected;
    private final SavegameContext context;
    private final GameClockService clock;
    private final RewindService rewinds;
    private final CalendarService calendar;
    private final PlayerResponseRepository responses;
    private final GamePromptRepository prompts;
    private final CycleQueue queue;
    private final CycleDispatcher dispatcher;
    private final TransactionTemplate tx;

    private String lastFactsRaw;
    private String lastResponsesRaw;
    /** Roadmap V2 R2-F1: responseIds in the last read player_responses.json (acknowledged once processed). */
    private List<String> lastResponseIds = List.of();
    private String lastResponsesSavegameId;
    private String lastContextRaw;
    private String lastAckRaw;
    private String lastInstructionsWritten;

    public BridgeSyncService(BridgeFiles files, SavegameRepository savegames, FactsSnapshotRepository snapshots,
                             OutboxInstructionRepository outbox, OutboxService outboxService,
                             DetectedSavegameRegistry detected, SavegameContext context, GameClockService clock,
                             RewindService rewinds, CalendarService calendar, PlayerResponseRepository responses,
                             GamePromptRepository prompts, CycleQueue queue, CycleDispatcher dispatcher,
                             PlatformTransactionManager transactions) {
        this.files = files;
        this.savegames = savegames;
        this.snapshots = snapshots;
        this.outbox = outbox;
        this.outboxService = outboxService;
        this.detected = detected;
        this.context = context;
        this.clock = clock;
        this.rewinds = rewinds;
        this.calendar = calendar;
        this.responses = responses;
        this.prompts = prompts;
        this.queue = queue;
        this.dispatcher = dispatcher;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Result of one cycle (used by tests and logging). */
    public record CycleResult(boolean marketContextRead, boolean factsIngested, boolean factsForUnlinkedSavegame,
                              int acksApplied, int instructionsWritten) {
    }

    public CycleResult runCycle() {
        drainAll(); // work left over by the previous cycle (or a restart) first
        boolean ctx = syncMarketContext();
        FactsOutcome facts = syncFacts();
        int acks = syncAcks();
        syncResponses();
        int written = writeInstructions();
        return new CycleResult(ctx, facts == FactsOutcome.INGESTED, facts == FactsOutcome.UNLINKED, acks, written);
    }

    /** Forget cached file contents so the next cycle re-reads everything (after linking a savegame). */
    public void resetCaches() {
        lastFactsRaw = null;
        lastContextRaw = null;
        lastAckRaw = null;
        lastInstructionsWritten = null;
        lastResponsesRaw = null;
        lastResponseIds = List.of();
        lastResponsesSavegameId = null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // queue

    private void drainAll() {
        for (Long id : queue.savegamesWithWork()) {
            drain(id);
        }
    }

    /** Delivers the queued work of a savegame in order; false = blocked by a failing listener (retried next cycle). */
    boolean drain(Long savegameId) {
        Optional<CycleEvent> next;
        while ((next = queue.next(savegameId)).isPresent()) {
            CycleEvent e = next.get();
            Object payload = queue.payload(e);
            Outcome outcome = payload instanceof TickWork work
                    ? clock.advance(savegameId, e.getId(), work)
                    : dispatcher.dispatch(savegameId, e.getId(), "", payload);
            if (outcome == Outcome.BLOCKED) {
                return false;
            }
            queue.complete(e.getId());
        }
        return true;
    }

    /** No new file of a savegame is read while earlier work of it is still queued (order of the game logic). */
    private boolean busy(Savegame sg) {
        return queue.hasWork(sg.getId());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reads

    boolean syncMarketContext() {
        Optional<BridgeFiles.Read<MarketContext>> read = files.read(files.marketContext(), MarketContext.class,
                BridgeValidator::validate);
        if (read.isEmpty() || read.get().raw().equals(lastContextRaw)) {
            return false;
        }
        MarketContext mc = read.get().doc();
        Optional<Savegame> sg = activeByBridgeId(mc.savegameId());
        if (sg.isEmpty()) {
            detected.updateMapName(mc.savegameId(), mc.mapName());
            lastContextRaw = read.get().raw();
            return true;
        }
        if (busy(sg.get())) {
            return false;
        }
        Long id = sg.get().getId();
        tx.executeWithoutResult(t -> {
            Savegame s = savegames.findById(id).orElseThrow();
            s.setMarketContextJson(read.get().raw());
            s.setMapName(mc.mapName());
            queue.enqueue(s, new BridgeEvents.MarketContextUpdated(id));
        });
        lastContextRaw = read.get().raw();
        drain(id);
        return true;
    }

    enum FactsOutcome { NONE, INGESTED, UNLINKED }

    FactsOutcome syncFacts() {
        Optional<BridgeFiles.Read<FarmFacts>> read = files.read(files.farmFacts(), FarmFacts.class, BridgeValidator::validate);
        if (read.isEmpty() || read.get().raw().equals(lastFactsRaw)) {
            return FactsOutcome.NONE;
        }
        FarmFacts f = read.get().doc();
        context.setCurrentBridgeSavegameId(f.savegameId());
        Optional<Savegame> sg = activeByBridgeId(f.savegameId());
        if (sg.isEmpty()) {
            // Onboarding step 3: the mod reports a new savegameId that is not linked yet.
            detected.report(f.savegameId(), null, f.gameTime(), f.liquidity().balance());
            lastFactsRaw = read.get().raw();
            return FactsOutcome.UNLINKED;
        }
        if (busy(sg.get())) {
            return FactsOutcome.NONE; // read again once the earlier work is done
        }
        Long id = sg.get().getId();
        tx.executeWithoutResult(t -> {
            Savegame s = savegames.findById(id).orElseThrow();
            boolean first = snapshots.countBySavegame(s) == 0;
            long previous = s.getCurrentGameTime();
            if (!first && f.gameTime() < previous) {
                // T-02: older state of the savegame loaded - registered before anyone reacts to the rewound snapshot
                rewinds.onRewind(s, previous, f.gameTime());
                queue.enqueue(s, new BridgeEvents.Rewound(id, previous, f.gameTime()));
            }
            // T-08: the game month is the FS25 period - the calendar anchor must be current before time advances
            calendar.update(s, f.calendar()).ifPresent(change -> queue.enqueue(s, change));
            FactsSnapshot snap = new FactsSnapshot();
            snap.setSavegame(s);
            snap.setGameTime(f.gameTime());
            snap.setReceivedAt(Instant.now());
            snap.setBalance(f.liquidity().balance());
            snap.setRawJson(read.get().raw());
            snapshots.save(snap);
            if (s.getFirstGameTime() == null) {
                s.setFirstGameTime(f.gameTime());
            }
            queue.enqueue(s, new BridgeEvents.FactsIngested(id, snap.getId(), f.gameTime(), first));
            queue.enqueue(s, new TickWork(first ? f.gameTime() : previous, f.gameTime(), null));
        });
        lastFactsRaw = read.get().raw();
        drain(id);
        return FactsOutcome.INGESTED;
    }

    int syncAcks() {
        Optional<BridgeFiles.Read<AckDocument>> read = files.read(files.ack(), AckDocument.class, BridgeValidator::validate);
        if (read.isEmpty()) {
            return 0;
        }
        if (read.get().raw().equals(lastAckRaw)) {
            // T-02: an unchanged ack file still answers a rewind detected after it was read (nothing lost)
            savegames.findByBridgeSavegameId(read.get().doc().savegameId())
                    .ifPresent(s -> rewinds.onAckDocument(s, read.get().doc()));
            return 0;
        }
        AckDocument doc = read.get().doc();
        Optional<Savegame> sg = savegames.findByBridgeSavegameId(doc.savegameId());
        if (sg.isEmpty()) {
            log.warn("instructions_ack.json belongs to unknown savegameId '{}' - ignored", doc.savegameId());
            lastAckRaw = read.get().raw();
            return 0;
        }
        if (busy(sg.get())) {
            return 0;
        }
        Long id = sg.get().getId();
        int applied = tx.execute(t -> {
            Savegame s = savegames.findById(id).orElseThrow();
            rewinds.onAckDocument(s, doc);
            int n = 0;
            for (BridgeDtos.Ack ack : doc.acks()) {
                Optional<OutboxInstruction> o = outbox.findByInstructionId(ack.instructionId());
                if (o.isEmpty()) {
                    continue;
                }
                OutboxInstruction ins = o.get();
                if (!Objects.equals(ins.getSavegame().getId(), id)) {
                    log.warn("Ack {} references another savegame - ignored", ack.instructionId());
                    continue;
                }
                if (ins.getStatus() != InstructionStatus.PENDING) {
                    continue;
                }
                InstructionStatus status = switch (ack.status()) {
                    case "APPLIED" -> InstructionStatus.APPLIED;
                    case "REJECTED" -> InstructionStatus.REJECTED;
                    default -> InstructionStatus.FAILED;
                };
                ins.setStatus(status);
                ins.setAckedAtGameTime(ack.appliedAtGameTime());
                ins.setAckMessage(ack.message());
                ins.setAckResultJson(outboxService.ackResultJson(ack.result())); // Roadmap V3 (R3-Q1)
                if (status != InstructionStatus.APPLIED) {
                    log.warn("Instruction {} was {} by the mod: {}", ins.getInstructionId(), status, ack.message());
                }
                queue.enqueue(s, new BridgeEvents.InstructionAcked(id, ins.getInstructionId(), status.name(),
                        ins.getRelatedEntityType(), ins.getRelatedEntityId(),
                        ack.result() == null ? java.util.Map.of() : ack.result()));
                n++;
            }
            if (doc.contractReports() != null) {
                for (BridgeDtos.ContractReport r : doc.contractReports()) {
                    queue.enqueue(s, new BridgeEvents.ContractReported(id, r.instructionId(),
                            r.deliveredQuantity() == null ? 0 : r.deliveredQuantity(),
                            r.maxQuantity() == null ? 0 : r.maxQuantity(), r.endReason()));
                }
            }
            return n;
        });
        lastAckRaw = read.get().raw();
        drain(id);
        return applied;
    }

    /**
     * Roadmap V2 R2-F1: reads export/player_responses.json. New answers are handed to the prompt handling (idempotent by
     * responseId); the ids of the file are acknowledged in the next instructions.json once processed.
     */
    void syncResponses() {
        Optional<BridgeFiles.Read<BridgeDtos.PlayerResponsesDocument>> read = files.read(files.playerResponses(),
                BridgeDtos.PlayerResponsesDocument.class, BridgeValidator::validate);
        if (read.isEmpty()) {
            return;
        }
        if (read.get().raw().equals(lastResponsesRaw)) {
            return;
        }
        BridgeDtos.PlayerResponsesDocument doc = read.get().doc();
        Optional<Savegame> sg = activeByBridgeId(doc.savegameId());
        if (sg.isEmpty()) {
            return; // not linked (yet): read again once it is
        }
        if (busy(sg.get())) {
            return;
        }
        Long id = sg.get().getId();
        List<BridgeDtos.PlayerAnswer> valid = doc.responses().stream().filter(BridgeValidator::valid).toList();
        if (valid.size() < doc.responses().size()) {
            log.warn("player_responses.json: {} invalid answers ignored", doc.responses().size() - valid.size());
        }
        tx.executeWithoutResult(t -> {
            Savegame s = savegames.findById(id).orElseThrow();
            List<BridgeDtos.PlayerAnswer> fresh = valid.stream()
                    .filter(a -> !responses.existsBySavegameAndResponseId(s, a.responseId())).toList();
            if (!fresh.isEmpty()) {
                queue.enqueue(s, new BridgeEvents.PlayerResponsesRead(id, fresh));
            }
        });
        lastResponsesRaw = read.get().raw();
        lastResponseIds = valid.stream().map(BridgeDtos.PlayerAnswer::responseId).toList();
        lastResponsesSavegameId = doc.savegameId();
        drain(id);
    }

    /**
     * Writes all PENDING instructions of the savegame the mod currently exports. The open questions are synchronised
     * first ({@link BridgeEvents.InstructionsWriting}) - only when no earlier work of the savegame is still queued.
     */
    int writeInstructions() {
        String bridgeId = context.currentBridgeSavegameId();
        if (bridgeId == null) {
            return 0;
        }
        Optional<Savegame> active = activeByBridgeId(bridgeId);
        if (active.isEmpty()) {
            return 0;
        }
        Long id = active.get().getId();
        if (!busy(active.get())) {
            tx.executeWithoutResult(t -> queue.enqueue(savegames.findById(id).orElseThrow(),
                    new BridgeEvents.InstructionsWriting(id)));
            drain(id);
        }
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        InstructionsDocument doc = readOnly.execute(t -> {
            Savegame sg = savegames.findById(id).orElseThrow();
            List<OutboxInstruction> pending = outboxService.pending(sg);
            List<Object> envelopes = new ArrayList<>();
            pending.forEach(o -> envelopes.add(outboxService.toEnvelope(o)));
            // R2-F1: processed answers of the file and questions that are no longer open
            List<String> acked = bridgeId.equals(lastResponsesSavegameId) && !lastResponseIds.isEmpty()
                    ? responses.findBySavegameAndResponseIdIn(sg, lastResponseIds).stream()
                            .map(de.farmpulse.rpsim.domain.PlayerResponse::getResponseId).sorted().toList()
                    : List.of();
            List<String> withdrawn = prompts.findBySavegameAndStatusInAndExpiresGameTimeGreaterThanEqualOrderByIdAsc(sg,
                            EnumSet.of(PromptStatus.ANSWERED, PromptStatus.WITHDRAWN), sg.getCurrentGameTime())
                    .stream().map(de.farmpulse.rpsim.domain.GamePrompt::getPromptId).toList();
            return new InstructionsDocument(bridgeId, envelopes, acked, withdrawn);
        });
        String key = bridgeId + doc.instructions() + doc.ackedResponses() + doc.withdrawnPrompts();
        if (!key.equals(lastInstructionsWritten)) {
            files.writeAtomic(files.instructions(), doc);         // human-readable, simulator/tooling
            files.writeAtomicXmlPayload(files.instructionsXml(), doc); // what the FS25 mod reads
            lastInstructionsWritten = key;
        }
        return doc.instructions().size();
    }

    private Optional<Savegame> activeByBridgeId(String bridgeId) {
        return savegames.findByBridgeSavegameId(bridgeId).filter(s -> s.getStatus() == SavegameStatus.ACTIVE);
    }
}
