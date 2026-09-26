package de.farmpulse.rpsim.notice;

import java.util.LinkedHashMap;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
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
 *   <li>farmland deals: {@link NegotiationEngine#onDealFailed} (ownership back to the previous owner)</li>
 *   <li>everything: a notice on the dashboard and a log line</li>
 * </ul>
 */
@Service
public class FailedInstructionService {

    public static final String RELATED = "INSTRUCTION";

    private static final Logger log = LoggerFactory.getLogger(FailedInstructionService.class);

    private final OutboxInstructionRepository outbox;
    private final SavegameRepository savegames;
    private final LoanService loans;
    private final PayrollScheduler payroll;
    private final NegotiationEngine negotiations;
    private final NoticeService notices;
    private final ContractBillingService billing;
    private final JsonMapper json;

    public FailedInstructionService(OutboxInstructionRepository outbox, SavegameRepository savegames, LoanService loans,
                                    PayrollScheduler payroll, NegotiationEngine negotiations, NoticeService notices,
                                    ContractBillingService billing, JsonMapper json) {
        this.outbox = outbox;
        this.savegames = savegames;
        this.loans = loans;
        this.payroll = payroll;
        this.negotiations = negotiations;
        this.notices = notices;
        this.billing = billing;
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
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        JsonNode p = json.readTree(ins.getPayloadJson());
        String reason = p.path("reason").asString("");
        if (ins.getBatchId() != null && ins.getType() == InstructionType.MONEY_TRANSACTION
                && outbox.findByBatchId(ins.getBatchId()).stream().anyMatch(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)) {
            // the money part of a farmland deal is reported together with its transfer
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
        } else if (ContractBillingService.RELATED.equals(related) && relatedId != null) {
            handled = billing.onPaymentFailed(relatedId);
        } else if (NegotiationEngine.RELATED.equals(related) && relatedId != null
                && ins.getType() == InstructionType.FARMLAND_TRANSFER) {
            handled = negotiations.onDealFailed(sg, relatedId);
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
        } else {
            d.put("fillType", p.path("fillType").asString(""));
            d.put("sellPoint", p.path("sellPoint").asString(""));
        }
        d.put("relatedType", related);
        d.put("relatedId", relatedId);
        d.put("handled", handled);
        notices.raise(sg, NoticeKind.INSTRUCTION_FAILED, d, RELATED, ins.getId());
    }
}
