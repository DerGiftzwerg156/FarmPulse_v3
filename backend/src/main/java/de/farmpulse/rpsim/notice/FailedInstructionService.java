package de.farmpulse.rpsim.notice;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.contract.LeaseService;
import de.farmpulse.rpsim.contract.MaintenanceService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.employee.TrainingService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.neighbor.NeighborMissionService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.theft.DieselTheftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-03: reacts to every instruction the mod did not execute (FAILED / REJECTED ack). Before, these acks were only
 * logged, so e.g. an installment counted as paid although no money left the farm.
 * <ul>
 *   <li>loan bookings: {@link LoanService#onBookingFailed} (installment due again, penalty/call-back escalate)</li>
 *   <li>salaries: {@link PayrollScheduler#onSalaryFailed} (salary stays due, salary-delay logic)</li>
 *   <li>trainings: {@link TrainingService#onBookingFailed} (training cancelled)</li>
 *   <li>farmland deals: {@link NegotiationEngine#onDealFailed} (ownership back to the previous owner)</li>
 *   <li>everything: a notice on the dashboard and a log line</li>
 * </ul>
 * Roadmap V3 (R3-Q1): an older mod refuses the new instruction types ({@link #ROADMAP_V3_TYPES}) with "unknown type …"
 * (REJECTED) or NOT_SUPPORTED; the notice then carries {@code modOutdated = true} and says "Mod aktualisieren". The
 * features that send these types cancel their deal themselves. Roadmap V3.1 (R31-Q1): the same for
 * {@link #ROADMAP_V31_TYPES}, Roadmap V3.2 (R32-Q1) for {@link #ROADMAP_V32_TYPES}.
 */
@Service
public class FailedInstructionService {

    public static final String RELATED = "INSTRUCTION";

    /** Roadmap V3 (R3-Q1): instruction types an older mod does not know. */
    public static final Set<InstructionType> ROADMAP_V3_TYPES = EnumSet.of(InstructionType.STORAGE_TRANSFER,
            InstructionType.MISSION_CREATE, InstructionType.VEHICLE_SPAWN, InstructionType.VEHICLE_REMOVE);

    /** Roadmap V3.1 (R31-Q1): instruction types an older mod does not know. */
    public static final Set<InstructionType> ROADMAP_V31_TYPES = EnumSet.of(InstructionType.FIELD_WORK,
            InstructionType.ANIMAL_TRANSFER, InstructionType.VEHICLE_FUEL);

    /** Roadmap V3.2 (R32-Q1): instruction types an older mod does not know. */
    public static final Set<InstructionType> ROADMAP_V32_TYPES = EnumSet.of(InstructionType.HUSBANDRY_TRANSFER);

    private static final Logger log = LoggerFactory.getLogger(FailedInstructionService.class);

    private final OutboxInstructionRepository outbox;
    private final de.farmpulse.rpsim.neighbor.FarmShopService farmShop;
    private final de.farmpulse.rpsim.market.BulkOrderService bulkOrders;
    private final de.farmpulse.rpsim.investor.InvestorService investors;
    private final SavegameRepository savegames;
    private final LoanService loans;
    private final PayrollScheduler payroll;
    private final NegotiationEngine negotiations;
    private final NoticeService notices;
    private final ContractBillingService billing;
    private final LeaseService lease;
    private final MaintenanceService maintenance;
    private final TrainingService training;
    private final JsonMapper json;
    private final NeighborTradeService trade;
    private final NeighborMissionService neighborMissions;
    private final de.farmpulse.rpsim.vehicle.VehicleTradeService vehicles;
    private final de.farmpulse.rpsim.contract.LeaseOutService leaseOut;
    private final de.farmpulse.rpsim.farmwork.ContractorWorkService contractorWork;
    private final de.farmpulse.rpsim.farmwork.MachineLoanService machineLoans;
    private final de.farmpulse.rpsim.neighbor.LivestockTradeService livestockTrade;
    private final DieselTheftService dieselThefts;

    public FailedInstructionService(OutboxInstructionRepository outbox, SavegameRepository savegames, LoanService loans,
                                    PayrollScheduler payroll, NegotiationEngine negotiations, NoticeService notices,
                                    ContractBillingService billing, LeaseService lease, MaintenanceService maintenance,
                                    TrainingService training, NeighborTradeService trade,
                                    NeighborMissionService neighborMissions, JsonMapper json,
                                    de.farmpulse.rpsim.neighbor.FarmShopService farmShop,
                                    de.farmpulse.rpsim.vehicle.VehicleTradeService vehicles,
                                    de.farmpulse.rpsim.contract.LeaseOutService leaseOut,
                                    de.farmpulse.rpsim.farmwork.ContractorWorkService contractorWork,
                                    de.farmpulse.rpsim.farmwork.MachineLoanService machineLoans,
                                    de.farmpulse.rpsim.neighbor.LivestockTradeService livestockTrade,
                                    DieselTheftService dieselThefts,
                                    de.farmpulse.rpsim.market.BulkOrderService bulkOrders,
                                    de.farmpulse.rpsim.investor.InvestorService investors) {
        this.bulkOrders = bulkOrders;
        this.investors = investors;
        this.dieselThefts = dieselThefts;
        this.machineLoans = machineLoans;
        this.livestockTrade = livestockTrade;
        this.leaseOut = leaseOut;
        this.contractorWork = contractorWork;
        this.farmShop = farmShop;
        this.vehicles = vehicles;
        this.trade = trade;
        this.neighborMissions = neighborMissions;
        this.training = training;
        this.outbox = outbox;
        this.savegames = savegames;
        this.loans = loans;
        this.payroll = payroll;
        this.negotiations = negotiations;
        this.notices = notices;
        this.billing = billing;
        this.lease = lease;
        this.maintenance = maintenance;
        this.json = json;
    }

    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if ("APPLIED".equals(e.status())) {
            return;
        }
        OutboxInstruction ins = outbox.findByInstructionId(e.instructionId()).orElse(null);
        if (ins == null) {
            return;
        }
        if (ins.getType() == InstructionType.NOTIFICATION) {
            return; // T-21: a missed in-game hint is no problem for the player - the mail is in the browser anyway
        }
        if (ins.getType() == InstructionType.EMPLOYEE_ROSTER) {
            return; // R2-A0: an older mod does not know the list - the helpers simply stay vanilla
        }
        if (ins.getType() == InstructionType.PROMPT) {
            return; // R2-F2: an older mod cannot ask - the decision stays in the browser
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        JsonNode p = json.readTree(ins.getPayloadJson());
        String reason = p.path("reason").asString("");
        if (ins.getBatchId() != null && ins.getType() == InstructionType.MONEY_TRANSACTION
                && outbox.findByBatchId(ins.getBatchId()).stream().anyMatch(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)) {
            // the money part of a farmland deal is reported together with its transfer
            return;
        }
        if (ins.getBatchId() != null && ins.getType() == InstructionType.MONEY_TRANSACTION
                && outbox.findByBatchId(ins.getBatchId()).stream().anyMatch(o -> newType(o.getType()))) {
            // Roadmap V3 (R3-Q1): likewise the money part of a goods or vehicle deal is reported with its instruction;
            // Roadmap V3.1 (R31-Q1): the same for field work and livestock trade
            return;
        }
        log.warn("Mod did not execute {} {} {} ({}): {}", ins.getInstructionId(), ins.getType(), reason, e.status(),
                ins.getAckMessage());
        String related = ins.getRelatedEntityType();
        Long relatedId = ins.getRelatedEntityId();
        boolean handled = false;
        if (LoanService.RELATED.equals(related) && relatedId != null) {
            handled = loans.onBookingFailed(sg, relatedId, ins.getInstructionId(), reason);
        } else if (SatisfactionService.RELATED.equals(related) && relatedId != null && "SALARY_PAYMENT".equals(reason)) {
            handled = payroll.onSalaryFailed(relatedId);
        } else if (SatisfactionService.RELATED.equals(related) && relatedId != null && "TRAINING".equals(reason)) {
            handled = training.onBookingFailed(relatedId, p.path("note").asString(""));
        } else if (ContractBillingService.RELATED.equals(related) && relatedId != null) {
            handled = billing.onPaymentFailed(relatedId);
        } else if (MaintenanceService.RELATED.equals(related) && relatedId != null) {
            handled = maintenance.onRepairFailed(relatedId, ins.getAckMessage());
        } else if (LeaseService.RELATED.equals(related) && relatedId != null) {
            handled = lease.onInstructionFailed(relatedId, ins.getType(),
                    "TO_PLAYER".equals(p.path("direction").asString("")), reason);
        } else if (de.farmpulse.rpsim.contract.LeaseOutService.RELATED.equals(related) && relatedId != null) {
            handled = leaseOut.onInstructionFailed(relatedId, ins.getType(), // R3-L1
                    "TO_PLAYER".equals(p.path("direction").asString("")));
        } else if (de.farmpulse.rpsim.farmwork.ContractorWorkService.RELATED.equals(related) && relatedId != null
                && (ins.getType() == InstructionType.FIELD_WORK || ins.getType() == InstructionType.STORAGE_TRANSFER)) {
            handled = contractorWork.onInstructionFailed(relatedId, ins.getAckMessage()); // R31-A1
        } else if (de.farmpulse.rpsim.farmwork.MachineLoanService.RELATED.equals(related) && relatedId != null) {
            handled = machineLoans.onInstructionFailed(relatedId, ins.getType(), ins.getAckMessage()); // R31-A2
        } else if (DieselTheftService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.VEHICLE_FUEL) {
            handled = dieselThefts.onInstructionFailed(relatedId, ins.getAckMessage()); // R31-D8: next night again
        } else if (de.farmpulse.rpsim.neighbor.LivestockTradeService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.ANIMAL_TRANSFER) {
            handled = livestockTrade.onInstructionFailed(relatedId, ins.getAckMessage()); // R31-A3
        } else if (NeighborTradeService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.STORAGE_TRANSFER) {
            handled = trade.onInstructionFailed(relatedId, ins.getAckMessage()); // R3-H3 / R3-H4
        } else if (de.farmpulse.rpsim.market.BulkOrderService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.STORAGE_TRANSFER) {
            handled = bulkOrders.onInstructionFailed(relatedId, ins.getAckMessage()); // R32-G2: the request stays open
        } else if (de.farmpulse.rpsim.investor.InvestorService.DELIVERY_RELATED.equals(related) && relatedId != null) {
            handled = investors.onDeliveryFailed(relatedId); // R32-I3: the delivery does not count
        } else if (de.farmpulse.rpsim.investor.InvestorService.PURCHASE_RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.STORAGE_TRANSFER) {
            handled = investors.onPurchaseFailed(relatedId); // R32-I3 P2: the request stays open
        } else if (de.farmpulse.rpsim.investor.InvestorOfferService.PAYMENT_RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.MONEY_TRANSACTION) {
            handled = investors.onPaymentFailed(relatedId); // R32-I: a refused payment stays open
        } else if (de.farmpulse.rpsim.neighbor.FarmShopService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.STORAGE_TRANSFER) {
            handled = farmShop.onInstructionFailed(relatedId, ins.getAckMessage()); // R3-M3
        } else if (de.farmpulse.rpsim.vehicle.VehicleTradeService.RELATED.equals(related) && relatedId != null
                && (ins.getType() == InstructionType.VEHICLE_SPAWN || ins.getType() == InstructionType.VEHICLE_REMOVE)) {
            handled = vehicles.onInstructionFailed(relatedId, ins.getType(), ins.getAckMessage()); // R3-V2 / R3-V3
        } else if (NeighborMissionService.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.MISSION_CREATE) {
            handled = neighborMissions.onInstructionFailed(relatedId); // R3-H5
        } else if (NegotiationEngine.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.FARMLAND_TRANSFER) {
            handled = negotiations.onDealFailed(sg, relatedId);
            // R3-K1: the repayment of a collateral value travels in the sale batch - its loan is corrected too
            if (ins.getBatchId() != null) {
                for (var o : outbox.findByBatchId(ins.getBatchId())) {
                    if (LoanService.RELATED.equals(o.getRelatedEntityType()) && o.getRelatedEntityId() != null) {
                        loans.onBookingFailed(sg, o.getRelatedEntityId(), o.getInstructionId(), reason);
                    }
                }
            }
        } else if (LoanService.COLLATERAL_RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.FARMLAND_TRANSFER) {
            handled = loans.onRealisationFailed(sg, relatedId); // R3-K1: realisation refused, the field stays
        }
        if (handled && ins.getType() == InstructionType.VEHICLE_FUEL && !modOutdated(ins.getType(), ins.getAckMessage())) {
            return; // R31-D8: a refused theft is tried again in the next night - the player does not learn of it
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instructionId", ins.getInstructionId());
        d.put("type", ins.getType().name());
        d.put("status", e.status());
        d.put("message", ins.getAckMessage());
        if (ins.getType() == InstructionType.MONEY_TRANSACTION) {
            d.put("reason", reason);
            d.put("amount", p.path("amount").asLong(0));
            d.put("note", p.path("note").asString(""));
        } else if (ins.getType() == InstructionType.FARMLAND_TRANSFER) {
            d.put("farmlandId", p.path("farmlandId").asInt());
            d.put("direction", p.path("direction").asString(""));
            d.put("price", p.path("price").asLong(0));
        } else if (newType(ins.getType())) {
            // Roadmap V3 (R3-Q1) / V3.1 (R31-Q1) / V3.2 (R32-Q1): the payload fields that name the deal
            for (String field : new String[] { "direction", "fillType", "amount", "missionType", "farmlandId",
                    "storeXmlFilename", "price", "vehicleId", "work", "fruitType", "husbandryUniqueId", "subType",
                    "count", "delta" }) {
                if (p.has(field)) {
                    d.put(field, p.get(field).isNumber() ? (Object) p.get(field).asDouble() : p.get(field).asString(""));
                }
            }
        } else {
            d.put("fillType", p.path("fillType").asString(""));
            d.put("sellPoint", p.path("sellPoint").asString(""));
        }
        d.put("modOutdated", modOutdated(ins.getType(), ins.getAckMessage()));
        d.put("relatedType", related);
        d.put("relatedId", relatedId);
        d.put("handled", handled);
        notices.raise(sg, NoticeKind.INSTRUCTION_FAILED, d, RELATED, ins.getId());
    }

    /** Roadmap V3 (R3-Q1) / V3.1 (R31-Q1) / V3.2 (R32-Q1): an instruction type an older mod does not know. */
    static boolean newType(InstructionType type) {
        return ROADMAP_V3_TYPES.contains(type) || ROADMAP_V31_TYPES.contains(type) || ROADMAP_V32_TYPES.contains(type);
    }

    /**
     * Roadmap V3 (R3-Q1): true when the mod refused a Roadmap V3 (or, R31-Q1, V3.1; R32-Q1, V3.2) instruction because it does not
     * know or execute the type yet (validation "unknown type …" → REJECTED, or an action missing in the mod →
     * NOT_SUPPORTED).
     */
    static boolean modOutdated(InstructionType type, String ackMessage) {
        if (!newType(type) || ackMessage == null) {
            return false;
        }
        return ackMessage.contains("unknown type") || ackMessage.contains("NOT_SUPPORTED");
    }
}
