package de.farmpulse.rpsim.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos.AckDocument;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.InstructionsDocument;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.GameClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bridge cycle: read market_context.json, farm_facts.json and instructions_ack.json, then write
 * instructions.json from the outbox. savegameId consistency is checked on every file (warning, no crash).
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
    private final ApplicationEventPublisher events;
    private final RewindService rewinds;

    private String lastFactsRaw;
    private String lastContextRaw;
    private String lastAckRaw;
    private String lastInstructionsWritten;

    public BridgeSyncService(BridgeFiles files, SavegameRepository savegames, FactsSnapshotRepository snapshots,
                             OutboxInstructionRepository outbox, OutboxService outboxService,
                             DetectedSavegameRegistry detected, SavegameContext context, GameClockService clock,
                             ApplicationEventPublisher events, RewindService rewinds) {
        this.files = files;
        this.savegames = savegames;
        this.snapshots = snapshots;
        this.outbox = outbox;
        this.outboxService = outboxService;
        this.detected = detected;
        this.context = context;
        this.clock = clock;
        this.events = events;
        this.rewinds = rewinds;
    }

    /** Result of one cycle (used by tests and logging). */
    public record CycleResult(boolean marketContextRead, boolean factsIngested, boolean factsForUnlinkedSavegame,
                              int acksApplied, int instructionsWritten) {
    }

    @Transactional
    public CycleResult runCycle() {
        boolean ctx = syncMarketContext();
        FactsOutcome facts = syncFacts();
        int acks = syncAcks();
        int written = writeInstructions();
        return new CycleResult(ctx, facts == FactsOutcome.INGESTED, facts == FactsOutcome.UNLINKED, acks, written);
    }

    /** Forget cached file contents so the next cycle re-reads everything (after linking a savegame). */
    public void resetCaches() {
        lastFactsRaw = null;
        lastContextRaw = null;
        lastAckRaw = null;
        lastInstructionsWritten = null;
    }

    boolean syncMarketContext() {
        Optional<BridgeFiles.Read<MarketContext>> read = files.read(files.marketContext(), MarketContext.class,
                BridgeValidator::validate);
        if (read.isEmpty() || read.get().raw().equals(lastContextRaw)) {
            return false;
        }
        MarketContext mc = read.get().doc();
        Optional<Savegame> sg = savegames.findByBridgeSavegameId(mc.savegameId())
                .filter(s -> s.getStatus() == SavegameStatus.ACTIVE);
        if (sg.isEmpty()) {
            detected.updateMapName(mc.savegameId(), mc.mapName());
            lastContextRaw = read.get().raw();
            return true;
        }
        Savegame s = sg.get();
        s.setMarketContextJson(read.get().raw());
        s.setMapName(mc.mapName());
        lastContextRaw = read.get().raw();
        events.publishEvent(new BridgeEvents.MarketContextUpdated(s.getId()));
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
        Optional<Savegame> sg = savegames.findByBridgeSavegameId(f.savegameId())
                .filter(s -> s.getStatus() == SavegameStatus.ACTIVE);
        if (sg.isEmpty()) {
            // Onboarding step 3: the mod reports a new savegameId that is not linked yet.
            detected.report(f.savegameId(), null, f.gameTime(), f.liquidity().balance());
            lastFactsRaw = read.get().raw();
            return FactsOutcome.UNLINKED;
        }
        Savegame s = sg.get();
        boolean first = snapshots.countBySavegame(s) == 0;
        if (!first && f.gameTime() < s.getCurrentGameTime()) {
            // T-02: older state of the savegame loaded - registered before anyone reacts to the rewound snapshot
            rewinds.onRewind(s, s.getCurrentGameTime(), f.gameTime());
        }
        FactsSnapshot snap = new FactsSnapshot();
        snap.setSavegame(s);
        snap.setGameTime(f.gameTime());
        snap.setReceivedAt(Instant.now());
        snap.setBalance(f.liquidity().balance());
        snap.setRawJson(read.get().raw());
        snapshots.save(snap);
        long previous = s.getCurrentGameTime();
        if (s.getFirstGameTime() == null) {
            s.setFirstGameTime(f.gameTime());
        }
        lastFactsRaw = read.get().raw();
        events.publishEvent(new BridgeEvents.FactsIngested(s.getId(), snap.getId(), f.gameTime(), first));
        clock.advance(s, first ? f.gameTime() : previous, f.gameTime());
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
        lastAckRaw = read.get().raw();
        Optional<Savegame> sg = savegames.findByBridgeSavegameId(doc.savegameId());
        if (sg.isEmpty()) {
            log.warn("instructions_ack.json belongs to unknown savegameId '{}' - ignored", doc.savegameId());
            return 0;
        }
        rewinds.onAckDocument(sg.get(), doc);
        int applied = 0;
        for (BridgeDtos.Ack ack : doc.acks()) {
            Optional<OutboxInstruction> o = outbox.findByInstructionId(ack.instructionId());
            if (o.isEmpty()) {
                continue;
            }
            OutboxInstruction ins = o.get();
            if (!Objects.equals(ins.getSavegame().getId(), sg.get().getId())) {
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
            if (status != InstructionStatus.APPLIED) {
                log.warn("Instruction {} was {} by the mod: {}", ins.getInstructionId(), status, ack.message());
            }
            events.publishEvent(new BridgeEvents.InstructionAcked(sg.get().getId(), ins.getInstructionId(), status.name(),
                    ins.getRelatedEntityType(), ins.getRelatedEntityId()));
            applied++;
        }
        if (doc.contractReports() != null) {
            for (BridgeDtos.ContractReport r : doc.contractReports()) {
                events.publishEvent(new BridgeEvents.ContractReported(sg.get().getId(), r.instructionId(),
                        r.deliveredQuantity() == null ? 0 : r.deliveredQuantity(),
                        r.maxQuantity() == null ? 0 : r.maxQuantity(), r.endReason()));
            }
        }
        return applied;
    }

    /** Writes all PENDING instructions of the savegame the mod currently exports. */
    int writeInstructions() {
        String bridgeId = context.currentBridgeSavegameId();
        if (bridgeId == null) {
            return 0;
        }
        Optional<Savegame> sg = savegames.findByBridgeSavegameId(bridgeId).filter(s -> s.getStatus() == SavegameStatus.ACTIVE);
        if (sg.isEmpty()) {
            return 0;
        }
        List<OutboxInstruction> pending = outboxService.pending(sg.get());
        List<Object> envelopes = new ArrayList<>();
        pending.forEach(o -> envelopes.add(outboxService.toEnvelope(o)));
        InstructionsDocument doc = new InstructionsDocument(bridgeId, envelopes);
        String key = bridgeId + envelopes;
        if (!key.equals(lastInstructionsWritten)) {
            files.writeAtomic(files.instructions(), doc);
            lastInstructionsWritten = key;
        }
        return envelopes.size();
    }
}
