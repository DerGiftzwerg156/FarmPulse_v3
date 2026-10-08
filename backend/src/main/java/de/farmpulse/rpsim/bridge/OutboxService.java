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

    /**
     * Roadmap V3 (R3-Q1): the optional result of an ack (e.g. vehicleId after VEHICLE_SPAWN) as JSON for
     * {@link OutboxInstruction#getAckResultJson()}; null when the ack carries none.
     */
    public String ackResultJson(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        return json.writeValueAsString(result);
    }

    /** Roadmap V3 (R3-Q1): the stored result of an ack, empty when there is none. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ackResult(OutboxInstruction ins) {
        if (ins.getAckResultJson() == null || ins.getAckResultJson().isBlank()) {
            return Map.of();
        }
        return json.readValue(ins.getAckResultJson(), Map.class);
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

    /** TODO T-22: repair of an own vehicle (maintenance contract). */
    @Transactional
    public OutboxInstruction repairVehicle(Savegame sg, String vehicleId, Related related) {
        return repairVehicle(sg, vehicleId, null, related);
    }

    /** Roadmap V2 R2-A6: partial repair down to targetDamage (0..1); null = full repair. */
    @Transactional
    public OutboxInstruction repairVehicle(Savegame sg, String vehicleId, Double targetDamage, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vehicleId", vehicleId);
        if (targetDamage != null) {
            p.put("targetDamage", Math.max(0, Math.min(1, targetDamage)));
        }
        return enqueue(sg, InstructionType.REPAIR_VEHICLE, p, null, null, related);
    }

    /** Roadmap V2 R2-A0: the complete employee list (the mod replaces its list). */
    @Transactional
    public OutboxInstruction employeeRoster(Savegame sg, List<Map<String, Object>> employees, String helperWageMode,
                                            boolean strictHelperLimit, Map<String, List<String>> trainingCategories) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("employees", employees);
        p.put("helperWageMode", helperWageMode);
        p.put("strictHelperLimit", strictHelperLimit);
        p.put("trainingCategories", trainingCategories); // "Schulungen": FS25 shop categories per training
        return enqueue(sg, InstructionType.EMPLOYEE_ROSTER, p, null, null, Related.none());
    }

    /** TODO T-22: farmland transfer without money (lease start / return). */
    @Transactional
    public OutboxInstruction farmlandTransfer(Savegame sg, int farmlandId, boolean toPlayer, String note, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("farmlandId", farmlandId);
        p.put("direction", toPlayer ? "TO_PLAYER" : "FROM_PLAYER");
        p.put("price", 0);
        return enqueue(sg, InstructionType.FARMLAND_TRANSFER, p, null, null, related);
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

    /**
     * Roadmap V3 R3-H3 / R3-H4 / R3-M3: goods into (IN) or out of (OUT) the own silos and the matching money booking as
     * one batch - STORAGE_TRANSFER first, so a refused transfer aborts the money part. {@code price} is the positive
     * amount; IN is booked as expense, OUT as income.
     */
    @Transactional
    public List<OutboxInstruction> storageDeal(Savegame sg, boolean in, String fillType, long liters, long price,
                                               MoneyReason reason, String note, Related related) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("direction", in ? "IN" : "OUT");
        p.put("fillType", fillType);
        p.put("amount", liters);
        OutboxInstruction transfer = enqueue(sg, InstructionType.STORAGE_TRANSFER, p, batchId, null, related);
        OutboxInstruction money = money(sg, in ? -price : price, reason, note, related, batchId, null);
        return List.of(transfer, money);
    }

    /**
     * Roadmap V3 R3-V2: a used machine delivered on a shop place. Not in a batch - loading is asynchronous, the mod
     * books {@code -price} itself in the loading callback and acknowledges with result.vehicleId.
     */
    @Transactional
    public OutboxInstruction vehicleSpawn(Savegame sg, String storeXmlFilename, int ageMonths, int operatingHours,
                                          double damage, double wear, long price, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("storeXmlFilename", storeXmlFilename);
        p.put("ageMonths", ageMonths);
        p.put("operatingHours", operatingHours);
        p.put("damage", damage);
        p.put("wear", wear);
        p.put("price", price);
        p.put("moneyReason", MoneyReason.VEHICLE_PURCHASE.name());
        return enqueue(sg, InstructionType.VEHICLE_SPAWN, p, null, null, related);
    }

    /**
     * Roadmap V3.1 R31-A2: a borrowed or demo machine - VEHICLE_SPAWN with price 0 (nothing is booked by the mod; the
     * rent runs as MACHINE_RENT per game day).
     */
    @Transactional
    public OutboxInstruction loanSpawn(Savegame sg, String storeXmlFilename, int ageMonths, int operatingHours,
                                       double damage, double wear, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("storeXmlFilename", storeXmlFilename);
        p.put("ageMonths", ageMonths);
        p.put("operatingHours", operatingHours);
        p.put("damage", damage);
        p.put("wear", wear);
        p.put("price", 0);
        p.put("moneyReason", MoneyReason.MACHINE_RENT.name());
        return enqueue(sg, InstructionType.VEHICLE_SPAWN, p, null, null, related);
    }

    /** Roadmap V3.1 R31-A2: a borrowed or demo machine goes back (VEHICLE_REMOVE without money). */
    @Transactional
    public OutboxInstruction vehicleRemove(Savegame sg, String vehicleId, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vehicleId", vehicleId);
        return enqueue(sg, InstructionType.VEHICLE_REMOVE, p, null, null, related);
    }

    /**
     * Roadmap V3 R3-V3: an own machine sold to a neighbour - VEHICLE_REMOVE first, then the proceeds (VEHICLE_SALE) in
     * the same batch, so a refused removal books nothing.
     */
    @Transactional
    public List<OutboxInstruction> vehicleSale(Savegame sg, String vehicleId, long price, String note, Related related) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vehicleId", vehicleId);
        OutboxInstruction remove = enqueue(sg, InstructionType.VEHICLE_REMOVE, p, batchId, null, related);
        OutboxInstruction money = money(sg, price, MoneyReason.VEHICLE_SALE, note, related, batchId, null);
        return List.of(remove, money);
    }

    /**
     * Roadmap V3.1 R31-A1: the contractor's work on an own field as one batch - FIELD_WORK first, for a harvest the
     * yield into the own silos (STORAGE_TRANSFER IN), then the fee (CONTRACTOR_FEE); a refused work books nothing.
     * {@code fruitType} only for SOW; {@code harvestFillType} / {@code harvestLiters} only for HARVEST.
     */
    @Transactional
    public List<OutboxInstruction> fieldWorkDeal(Savegame sg, int farmlandId, String work, String fruitType,
                                                 String harvestFillType, long harvestLiters, long price, String note,
                                                 Related related) {
        return fieldWorkDeal(sg, null, farmlandId, work, fruitType, harvestFillType, harvestLiters, price, note, related);
    }

    /**
     * The same, appended to the batch {@code batchId} (null = a new batch): the works of one contractor order go to the
     * mod as one batch in their order, so a refused work aborts the works after it (owner decisions 2026-10-06).
     */
    @Transactional
    public List<OutboxInstruction> fieldWorkDeal(Savegame sg, String batchId, int farmlandId, String work,
                                                 String fruitType, String harvestFillType, long harvestLiters, long price,
                                                 String note, Related related) {
        if (batchId == null) {
            batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("farmlandId", farmlandId);
        p.put("work", work);
        if (fruitType != null) {
            p.put("fruitType", fruitType);
        }
        List<OutboxInstruction> list = new java.util.ArrayList<>();
        list.add(enqueue(sg, InstructionType.FIELD_WORK, p, batchId, null, related));
        if (harvestFillType != null && harvestLiters > 0) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("direction", "IN");
            t.put("fillType", harvestFillType);
            t.put("amount", harvestLiters);
            list.add(enqueue(sg, InstructionType.STORAGE_TRANSFER, t, batchId, null, related));
        }
        list.add(money(sg, -price, MoneyReason.CONTRACTOR_FEE, note, related, batchId, null));
        return list;
    }

    /**
     * Roadmap V3.1 R31-A3: animals into (IN, purchase) or out of (OUT, sale) an own husbandry and the money as one batch -
     * ANIMAL_TRANSFER first, so a refused transfer books nothing. {@code price} is the positive total.
     */
    @Transactional
    public List<OutboxInstruction> animalDeal(Savegame sg, boolean in, String husbandryUniqueId, String subType, int count,
                                              Integer ageMonths, long price, String note, Related related) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("husbandryUniqueId", husbandryUniqueId);
        p.put("subType", subType);
        p.put("count", count);
        if (ageMonths != null) {
            p.put("age", ageMonths);
        }
        p.put("direction", in ? "IN" : "OUT");
        OutboxInstruction transfer = enqueue(sg, InstructionType.ANIMAL_TRANSFER, p, batchId, null, related);
        OutboxInstruction money = money(sg, in ? -price : price, in ? MoneyReason.LIVESTOCK_PURCHASE
                : MoneyReason.LIVESTOCK_SALE, note, related, batchId, null);
        return List.of(transfer, money);
    }

    /**
     * Roadmap V3.2 R32-I3 (W1 / W2): goods out of the own silos without money - the delivery is the consideration to an
     * investor (like the contractor's harvest, R31-A1, a STORAGE_TRANSFER without its own booking).
     */
    @Transactional
    public OutboxInstruction storageTransfer(Savegame sg, boolean in, String fillType, long liters, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("direction", in ? "IN" : "OUT");
        p.put("fillType", fillType);
        p.put("amount", liters);
        return enqueue(sg, InstructionType.STORAGE_TRANSFER, p, null, null, related);
    }

    /** Roadmap V3.2 R32-I3 (W3): milk out of the storage of an own husbandry (HUSBANDRY_TRANSFER, R32-Q1), no money. */
    @Transactional
    public OutboxInstruction husbandryTransfer(Savegame sg, String husbandryUniqueId, String fillType, long liters,
                                               Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("husbandryUniqueId", husbandryUniqueId);
        p.put("fillType", fillType);
        p.put("amount", liters);
        return enqueue(sg, InstructionType.HUSBANDRY_TRANSFER, p, null, null, related);
    }

    /** Roadmap V3.2 R32-I3 (A1): animals out of an own husbandry without money (ANIMAL_TRANSFER OUT, R31-A3). */
    @Transactional
    public OutboxInstruction animalTransfer(Savegame sg, String husbandryUniqueId, String subType, int count,
                                            Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("husbandryUniqueId", husbandryUniqueId);
        p.put("subType", subType);
        p.put("count", count);
        p.put("direction", "OUT");
        return enqueue(sg, InstructionType.ANIMAL_TRANSFER, p, null, null, related);
    }

    /**
     * Roadmap V3.1 R31-D8: takes diesel from a parked own vehicle ({@code delta} litres, negative) - not before
     * {@code gameTimeEarliest}; the ack result carries the litres taken.
     */
    @Transactional
    public OutboxInstruction vehicleFuel(Savegame sg, String vehicleId, long delta, Long gameTimeEarliest, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vehicleId", vehicleId);
        p.put("delta", delta);
        return enqueue(sg, InstructionType.VEHICLE_FUEL, p, null, gameTimeEarliest, related);
    }

    /** Roadmap V3 R3-H5: a real contract of the game on the field of an NPC farmland (result.missionId in the ack). */
    @Transactional
    public OutboxInstruction missionCreate(Savegame sg, String missionType, int farmlandId, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("missionType", missionType);
        p.put("farmlandId", farmlandId);
        return enqueue(sg, InstructionType.MISSION_CREATE, p, null, null, related);
    }

    /**
     * TODO T-21: in-game notification ({@code g_currentMission:addIngameNotification}). The mod skips it without
     * showing when the game time is past {@code expiresAtGameTime} (e.g. processed late after loading a savegame).
     */
    public OutboxInstruction notification(Savegame sg, String text, String level, long expiresAtGameTime, Related related) {
        return notification(sg, text, level, expiresAtGameTime, null, related);
    }

    /** Roadmap V3.2 R32-G3: the same, shown not before {@code gameTimeEarliest} (e.g. the start of a delivery month). */
    public OutboxInstruction notification(Savegame sg, String text, String level, long expiresAtGameTime,
                                          Long gameTimeEarliest, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", text);
        p.put("level", level);
        p.put("expiresAtGameTime", expiresAtGameTime);
        return enqueue(sg, InstructionType.NOTIFICATION, p, null, gameTimeEarliest, related);
    }

    /**
     * Roadmap V3.2 R32-G3: moves an instruction the mod has not taken yet ("Tage je Periode" changed): new
     * {@code gameTimeEarliest} and the given payload fields. The instructions file is rebuilt from the pending
     * instructions in every cycle, so the mod reads the new values. False when the instruction is unknown or no longer
     * pending (the mod keeps what it has).
     */
    @SuppressWarnings("unchecked")
    public boolean reschedulePending(String instructionId, Long gameTimeEarliest, Map<String, Object> payloadChanges) {
        if (instructionId == null) {
            return false;
        }
        OutboxInstruction o = repo.findByInstructionId(instructionId).orElse(null);
        if (o == null || o.getStatus() != InstructionStatus.PENDING) {
            return false;
        }
        Map<String, Object> p = json.readValue(o.getPayloadJson(), LinkedHashMap.class);
        p.putAll(payloadChanges);
        o.setPayloadJson(json.writeValueAsString(p));
        o.setGameTimeEarliest(gameTimeEarliest);
        return true;
    }

    /**
     * Roadmap V2 R2-F2: yes/no question in the game. The mod drops it without showing when it is processed after
     * {@code expiresGameTime}; the labels name the meaning of the game's yes / no buttons.
     */
    public OutboxInstruction prompt(Savegame sg, String promptId, String title, String text, String yesLabel,
                                    String noLabel, long expiresGameTime, Related related) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("promptId", promptId);
        p.put("title", title);
        p.put("text", text);
        if (yesLabel != null) {
            p.put("yesLabel", yesLabel);
        }
        if (noLabel != null) {
            p.put("noLabel", noLabel);
        }
        p.put("expiresGameTime", expiresGameTime);
        return enqueue(sg, InstructionType.PROMPT, p, null, null, related);
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
