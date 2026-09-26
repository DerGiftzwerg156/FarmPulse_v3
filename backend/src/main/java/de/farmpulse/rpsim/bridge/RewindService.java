package de.farmpulse.rpsim.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos.AckDocument;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.BridgeRewind;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.RewindStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.NoticeService;
import de.farmpulse.rpsim.repository.BridgeRewindRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-02 "Laden ohne Speichern": when the player reloads an older state of the savegame, the bookings the mod executed
 * after that point are missing in the game while the backend still counts them as APPLIED.
 * <ol>
 *   <li>The rewind is recognised from the game time of the first snapshot after loading ({@link #onRewind}).</li>
 *   <li>The next instructions_ack.json rebuilt by the mod from the reloaded savegame shows which acknowledged
 *       instructions are missing ({@link #onAckDocument}); an ack file still written before the reload is recognised
 *       and skipped.</li>
 *   <li>Missing instructions go back to PENDING with the same instructionId - the mod executes them again because
 *       they are not in its (reloaded) processed list. Up to {@code rpsim.bridge.rewind-auto-resend-max-hours} this
 *       happens automatically, deeper rewinds ask the player (notice on the dashboard).</li>
 * </ol>
 * The remaining tool state (mails, trust, negotiations) is deliberately NOT rolled back.
 */
@Service
public class RewindService {

    public static final String RELATED = "REWIND";
    public static final String RESEND = "RESEND";
    public static final String KEEP = "KEEP";

    private static final Logger log = LoggerFactory.getLogger(RewindService.class);
    private static final Set<RewindStatus> OPEN = EnumSet.of(RewindStatus.AWAITING_ACK, RewindStatus.AWAITING_PLAYER);
    private static final Set<InstructionType> RESENDABLE = EnumSet.of(InstructionType.MONEY_TRANSACTION,
            InstructionType.PRICE_EVENT, InstructionType.FARMLAND_TRANSFER);

    private final BridgeRewindRepository rewinds;
    private final OutboxInstructionRepository outbox;
    private final NoticeService notices;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final JsonMapper json;

    public RewindService(BridgeRewindRepository rewinds, OutboxInstructionRepository outbox, NoticeService notices,
                         DiaryService diary, RpsimProperties props, JsonMapper json) {
        this.rewinds = rewinds;
        this.outbox = outbox;
        this.notices = notices;
        this.diary = diary;
        this.props = props;
        this.json = json;
    }

    /** A snapshot moved game time backwards. Several reloads before the next ack merge into one record. */
    @Transactional
    public BridgeRewind onRewind(Savegame sg, long previousGameTime, long rewoundToGameTime) {
        log.info("Savegame {} was reloaded at an earlier state ({} -> {}), checking for lost bookings", sg.getId(),
                previousGameTime, rewoundToGameTime);
        for (BridgeRewind r : rewinds.findBySavegameAndStatusInOrderByIdAsc(sg, EnumSet.of(RewindStatus.AWAITING_ACK))) {
            r.setRewoundToGameTime(Math.min(r.getRewoundToGameTime(), rewoundToGameTime));
            r.setPreviousGameTime(Math.max(r.getPreviousGameTime(), previousGameTime));
            return r;
        }
        BridgeRewind r = new BridgeRewind();
        r.setSavegame(sg);
        r.setPreviousGameTime(previousGameTime);
        r.setRewoundToGameTime(rewoundToGameTime);
        r.setStatus(RewindStatus.AWAITING_ACK);
        r.setCreatedAt(Instant.now());
        return rewinds.save(r);
    }

    /** Evaluates an ack document against every rewind that waits for it. Called before the acks are applied. */
    @Transactional
    public void onAckDocument(Savegame sg, AckDocument doc) {
        for (BridgeRewind r : rewinds.findBySavegameAndStatusInOrderByIdAsc(sg, EnumSet.of(RewindStatus.AWAITING_ACK))) {
            evaluate(sg, r, doc);
        }
    }

    void evaluate(Savegame sg, BridgeRewind r, AckDocument doc) {
        Map<String, BridgeDtos.Ack> acked = new HashMap<>();
        if (doc.acks() != null) {
            doc.acks().forEach(a -> acked.put(a.instructionId(), a));
        }
        List<OutboxInstruction> candidates = candidates(sg, r);
        for (OutboxInstruction c : candidates) {
            BridgeDtos.Ack a = acked.get(c.getInstructionId());
            if (c.getAckedAtGameTime() > r.getRewoundToGameTime() && a != null && a.appliedAtGameTime() != null
                    && Math.abs(a.appliedAtGameTime() - c.getAckedAtGameTime()) <= 1) {
                // still the ack file written before the reload - wait for the rebuilt one
                return;
            }
        }
        Set<String> alreadyClaimed = claimedByOtherRewinds(sg, r);
        List<OutboxInstruction> lost = candidates.stream()
                .filter(c -> !acked.containsKey(c.getInstructionId()))
                .filter(c -> !alreadyClaimed.contains(c.getInstructionId()))
                .toList();
        if (lost.isEmpty()) {
            r.setStatus(RewindStatus.NOTHING_LOST);
            r.setResolvedAt(Instant.now());
            return;
        }
        r.setLostInstructionIds(String.join(",", lost.stream().map(OutboxInstruction::getInstructionId).toList()));
        double depthHours = (r.getPreviousGameTime() - r.getRewoundToGameTime()) / (double) GameTime.MS_PER_HOUR;
        Map<String, Object> details = details(r, lost);
        if (depthHours <= props.getBridge().getRewindAutoResendMaxHours()) {
            resend(lost);
            r.setStatus(RewindStatus.RESENT);
            r.setResolvedAt(Instant.now());
            notices.raise(sg, NoticeKind.REWIND_RESENT, details, RELATED, r.getId());
            diary.addAuto(sg, "BRIDGE", "Spielstand ohne Speichern neu geladen",
                    lost.size() + " Buchungen fehlten im Spiel und wurden erneut gesendet.", RELATED, r.getId());
        } else {
            r.setStatus(RewindStatus.AWAITING_PLAYER);
            notices.raise(sg, NoticeKind.REWIND_DECISION, details, RELATED, r.getId());
            diary.addAuto(sg, "BRIDGE", "Älterer Spielstand geladen",
                    lost.size() + " Buchungen fehlen im Spiel. Entscheidung im Dashboard: nachbuchen oder Tool-Stand beibehalten.",
                    RELATED, r.getId());
        }
    }

    /** APPLIED instructions acknowledged after (reloaded point - lookback): candidates for "lost". */
    List<OutboxInstruction> candidates(Savegame sg, BridgeRewind r) {
        long from = r.getRewoundToGameTime() - GameTime.hours(props.getBridge().getRewindLookbackHours());
        return outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.APPLIED).stream()
                .filter(o -> RESENDABLE.contains(o.getType()))
                .filter(o -> o.getAckedAtGameTime() != null && o.getAckedAtGameTime() > from)
                .toList();
    }

    private Set<String> claimedByOtherRewinds(Savegame sg, BridgeRewind self) {
        Set<String> ids = new HashSet<>();
        for (BridgeRewind o : rewinds.findBySavegameAndStatusInOrderByIdAsc(sg, EnumSet.of(RewindStatus.AWAITING_PLAYER))) {
            if (!o.getId().equals(self.getId())) {
                ids.addAll(lostIds(o));
            }
        }
        return ids;
    }

    private Map<String, Object> details(BridgeRewind r, List<OutboxInstruction> lost) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("rewindId", r.getId());
        d.put("previousGameTime", r.getPreviousGameTime());
        d.put("rewoundToGameTime", r.getRewoundToGameTime());
        long money = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (OutboxInstruction o : lost) {
            JsonNode p = json.readTree(o.getPayloadJson());
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("instructionId", o.getInstructionId());
            i.put("type", o.getType().name());
            if (o.getType() == InstructionType.MONEY_TRANSACTION) {
                long amount = p.path("amount").asLong(0);
                money += amount;
                i.put("amount", amount);
                i.put("reason", p.path("reason").asString(""));
                i.put("note", p.path("note").asString(""));
            } else if (o.getType() == InstructionType.FARMLAND_TRANSFER) {
                i.put("farmlandId", p.path("farmlandId").asInt());
                i.put("direction", p.path("direction").asString(""));
            } else {
                i.put("fillType", p.path("fillType").asString(""));
                i.put("sellPoint", p.path("sellPoint").asString(""));
            }
            items.add(i);
        }
        d.put("count", lost.size());
        d.put("moneyTotal", money);
        d.put("instructions", items);
        return d;
    }

    private void resend(List<OutboxInstruction> lost) {
        for (OutboxInstruction o : lost) {
            o.setStatus(InstructionStatus.PENDING);
            o.setAckedAtGameTime(null);
            o.setAckMessage(null);
        }
        log.info("Re-sending {} instructions lost by the reload: {}", lost.size(),
                lost.stream().map(OutboxInstruction::getInstructionId).toList());
    }

    private static List<String> lostIds(BridgeRewind r) {
        if (r.getLostInstructionIds() == null || r.getLostInstructionIds().isBlank()) {
            return List.of();
        }
        return Arrays.asList(r.getLostInstructionIds().split(","));
    }

    /** Player decision for a deep rewind: RESEND (book again) or KEEP (keep the tool state, book nothing). */
    @Transactional
    public BridgeRewind decide(Savegame sg, Long rewindId, String decision) {
        BridgeRewind r = rewinds.findById(rewindId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("rewind " + rewindId));
        if (r.getStatus() != RewindStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("REWIND_ALREADY_DECIDED", "Diese Entscheidung wurde bereits getroffen.");
        }
        if (RESEND.equals(decision)) {
            List<OutboxInstruction> lost = lostIds(r).stream()
                    .map(outbox::findByInstructionId)
                    .flatMap(java.util.Optional::stream)
                    .filter(o -> o.getStatus() == InstructionStatus.APPLIED)
                    .toList();
            resend(lost);
            r.setStatus(RewindStatus.RESENT);
            diary.addAuto(sg, "BRIDGE", "Buchungen nachgebucht", lost.size() + " fehlende Buchungen wurden erneut gesendet.",
                    RELATED, r.getId());
        } else if (KEEP.equals(decision)) {
            r.setStatus(RewindStatus.KEPT);
            diary.addAuto(sg, "BRIDGE", "Tool-Stand beibehalten",
                    "Die fehlenden Buchungen werden nicht nachgebucht.", RELATED, r.getId());
        } else {
            throw new BusinessRuleException("INVALID_DECISION", "Unbekannte Entscheidung: " + decision);
        }
        r.setResolvedAt(Instant.now());
        return r;
    }

    /** Resolves a REWIND_DECISION notice through the rewind decision. */
    @Transactional
    public void decideByNotice(Savegame sg, Notice n, String decision) {
        decide(sg, n.getRelatedEntityId(), decision);
        notices.resolve(n, decision);
    }

    /**
     * Farmlands whose ownership must not be reconciled from the facts while a rewind is open: the reloaded game shows
     * the old owner, but the transfer will be re-sent (or the player still decides).
     */
    public Set<Integer> farmlandsOnHold(Savegame sg) {
        Set<Integer> ids = new HashSet<>();
        for (BridgeRewind r : rewinds.findBySavegameAndStatusInOrderByIdAsc(sg, OPEN)) {
            List<OutboxInstruction> list = r.getStatus() == RewindStatus.AWAITING_ACK ? candidates(sg, r)
                    : lostIds(r).stream().map(outbox::findByInstructionId).flatMap(java.util.Optional::stream).toList();
            list.stream().filter(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)
                    .forEach(o -> ids.add(json.readTree(o.getPayloadJson()).path("farmlandId").asInt()));
        }
        return ids;
    }

    public List<BridgeRewind> list(Savegame sg) {
        return rewinds.findBySavegameOrderByIdAsc(sg);
    }
}
