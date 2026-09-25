package de.farmpulse.rpsim.bridge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates bridge instructions. Services of the fact layer are the only callers; the payload is always a
 * deterministic formula result. Backing store of instructions.json.
 */
@Service
public class OutboxService {

    private final OutboxInstructionRepository repo;
    private final JsonMapper json;

    public OutboxService(OutboxInstructionRepository repo, JsonMapper json) {
        this.repo = repo;
        this.json = json;
    }

    public static String newInstructionId() {
        return "ins_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Relation of an instruction to the entity that caused it (for reconciliation / ack handling). */
    public record Related(String type, Long id) {
        public static Related none() {
            return new Related(null, null);
        }
    }

    @Transactional
    public OutboxInstruction money(Savegame sg, long amount, MoneyReason reason, String note, Related related) {
        return money(sg, amount, reason, note, related, null, null);
    }

    @Transactional
    public OutboxInstruction money(Savegame sg, long amount, MoneyReason reason, String note, Related related,
                                   String batchId, Long gameTimeEarliest) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", amount);
        p.put("reason", reason.name());
        if (note != null) {
            p.put("note", note);
        }
        return enqueue(sg, InstructionType.MONEY_TRANSACTION, p, batchId, gameTimeEarliest, related);
    }

    @Transactional
    public OutboxInstruction priceMultiplier(Savegame sg, String fillType, String sellPoint, double peakMultiplier,
                                             double rampUpHours, double holdHours, double decayHours, long gameTimeEarliest,
                                             Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("priceMode", "MULTIPLIER");
        p.put("fillType", fillType);
        p.put("sellPoint", sellPoint);
        p.put("peakMultiplier", peakMultiplier);
        p.put("rampUpHours", rampUpHours);
        p.put("holdHours", holdHours);
        p.put("decayHours", decayHours);
        return enqueue(sg, InstructionType.PRICE_EVENT, p, null, gameTimeEarliest, related);
    }

    @Transactional
    public OutboxInstruction priceFixed(Savegame sg, String fillType, String sellPoint, long fixedPrice, long maxQuantity,
                                        long deadlineGameTime, Long gameTimeEarliest, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("priceMode", "FIXED");
        p.put("fillType", fillType);
        p.put("sellPoint", sellPoint);
        p.put("fixedPrice", fixedPrice);
        p.put("maxQuantity", maxQuantity);
        p.put("deadlineGameTime", deadlineGameTime);
        return enqueue(sg, InstructionType.PRICE_EVENT, p, null, gameTimeEarliest, related);
    }

    /**
     * Farmland ownership transfer AND the matching money transaction as two entries of the same batch, so
     * ownership and money never diverge (technical concept "Abschluss").
     */
    @Transactional
    public List<OutboxInstruction> farmlandDeal(Savegame sg, int farmlandId, boolean toPlayer, long price, String note,
                                                Related related) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("farmlandId", farmlandId);
        p.put("direction", toPlayer ? "TO_PLAYER" : "FROM_PLAYER");
        p.put("price", price);
        OutboxInstruction transfer = enqueue(sg, InstructionType.FARMLAND_TRANSFER, p, batchId, null, related);
        OutboxInstruction money = money(sg, toPlayer ? -price : price,
                toPlayer ? MoneyReason.FARMLAND_PURCHASE : MoneyReason.FARMLAND_SALE, note, related, batchId, null);
        return List.of(transfer, money);
    }

    private OutboxInstruction enqueue(Savegame sg, InstructionType type, Map<String, Object> payload, String batchId,
                                      Long gameTimeEarliest, Related related) {
        OutboxInstruction o = new OutboxInstruction();
        o.setSavegame(sg);
        o.setInstructionId(newInstructionId());
        o.setBatchId(batchId);
        o.setType(type);
        o.setPayloadJson(json.writeValueAsString(payload));
        o.setStatus(InstructionStatus.PENDING);
        o.setGameTimeEarliest(gameTimeEarliest);
        o.setCreatedAtGameTime(sg.getCurrentGameTime());
        o.setCreatedAt(Instant.now());
        if (related != null) {
            o.setRelatedEntityType(related.type());
            o.setRelatedEntityId(related.id());
        }
        return repo.save(o);
    }

    /** Envelope for instructions.json exactly as in the mod schema. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toEnvelope(OutboxInstruction o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instructionId", o.getInstructionId());
        m.put("type", o.getType().name());
        if (o.getBatchId() != null) {
            m.put("batchId", o.getBatchId());
        }
        if (o.getGameTimeEarliest() != null) {
            m.put("gameTimeEarliest", o.getGameTimeEarliest());
        }
        m.putAll(json.readValue(o.getPayloadJson(), LinkedHashMap.class));
        return m;
    }

    public List<OutboxInstruction> pending(Savegame sg) {
        return repo.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING);
    }
}
