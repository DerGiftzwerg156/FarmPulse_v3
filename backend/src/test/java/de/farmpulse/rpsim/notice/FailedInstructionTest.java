package de.farmpulse.rpsim.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.LoanPaymentRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO.md T-03: the backend reacts to bookings the mod refused (FAILED / INSUFFICIENT_FUNDS). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FailedInstructionTest {

    @Autowired Fixtures fx;
    @Autowired LoanService loans;
    @Autowired LoanPaymentRepository payments;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired FailedInstructionService failed;
    @Autowired NoticeService notices;
    @Autowired LiquidityService liquidity;
    @Autowired TrustEventRepository trustEvents;
    @Autowired HiringService hiring;
    @Autowired PayrollScheduler payroll;
    @Autowired NegotiationEngine negotiations;
    @Autowired FarmlandOwnershipService ownership;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
    }

    /** Simulates the ack path of BridgeSyncService for a refused instruction. */
    private void refuse(OutboxInstruction ins) {
        ins.setStatus(InstructionStatus.FAILED);
        ins.setAckedAtGameTime(sg.getCurrentGameTime());
        ins.setAckMessage(LiquidityService.INSUFFICIENT_FUNDS);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), "FAILED",
                ins.getRelatedEntityType(), ins.getRelatedEntityId()));
    }

    private List<OutboxInstruction> withReason(String reason) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getPayloadJson().contains(reason)).toList();
    }

    @Test
    void refusedInstallmentIsReversedAndBecomesDueAgain() {
        fx.snapshot(sg, 1_000_000);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Traktor", true, null);
        long dueAt = l.getNextDueGameTime();
        sg.setCurrentGameTime(dueAt);
        loans.processDueInstallments(sg);
        assertThat(l.getPaidInstallments()).isEqualTo(1);
        assertThat(l.getRemainingAmount()).isEqualTo(11_000);
        OutboxInstruction rate = withReason("CREDIT_INSTALLMENT").getFirst();

        refuse(rate);

        assertThat(l.getPaidInstallments()).isZero();
        assertThat(l.getRemainingAmount()).isEqualTo(12_000);
        assertThat(l.getNextDueGameTime()).isEqualTo(dueAt);
        assertThat(payments.findFirstByInstructionId(rate.getInstructionId()).orElseThrow().getType())
                .isEqualTo(LoanPaymentType.REVERSED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank)).extracting(e -> e.getReason())
                .containsExactly(TrustReason.ON_TIME_PAYMENT, TrustReason.ON_TIME_PAYMENT_REVERSED);
        assertThat(notices.open(sg)).singleElement().satisfies(n -> {
            assertThat(n.getKind()).isEqualTo(NoticeKind.INSTRUCTION_FAILED);
            assertThat(notices.details(n)).containsEntry("handled", true).containsEntry("reason", "CREDIT_INSTALLMENT");
        });

        // the stale snapshot (1 000 000) is not trusted any more: no new booking, the rate counts as missed
        assertThat(liquidity.available(sg)).isLessThanOrEqualTo(0);
        loans.processDueInstallments(sg);
        assertThat(withReason("CREDIT_INSTALLMENT")).hasSize(1);
        assertThat(l.getMissedInstallments()).isEqualTo(1);
        assertThat(l.getOverdueSinceGameTime()).isEqualTo(dueAt);

        // a newer snapshot with enough money -> the installment is booked again
        sg.setCurrentGameTime(dueAt + GameTime.hours(2));
        fx.snapshot(sg, 50_000);
        loans.processDueInstallments(sg);
        assertThat(withReason("CREDIT_INSTALLMENT")).hasSize(2);
        assertThat(l.getPaidInstallments()).isEqualTo(1);
    }

    @Test
    void refusedPenaltyMovesToTheNextEscalationStage() {
        fx.snapshot(sg, 0);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        sg.setCurrentGameTime(l.getNextDueGameTime() + GameTime.days(3));
        loans.processDueInstallments(sg);
        assertThat(l.getEscalationLevel()).isEqualTo(2);
        refuse(withReason("CREDIT_PENALTY").getFirst());
        assertThat(l.getEscalationLevel()).isEqualTo(3);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank)).extracting(e -> e.getReason())
                .contains(TrustReason.PAYMENT_ESCALATION);
    }

    @Test
    void refusedCallbackLeavesTheLoanDefaultedUntilItCanBeCollected() {
        fx.snapshot(sg, 0);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        long due = l.getNextDueGameTime();
        for (int i = 0; i <= 3; i++) {
            sg.setCurrentGameTime(due + GameTime.days(5 + i));
            loans.processDueInstallments(sg);
        }
        assertThat(l.getStatus()).isEqualTo(LoanStatus.CALLED);
        refuse(withReason("CREDIT_CALLBACK").getFirst());
        assertThat(l.getStatus()).isEqualTo(LoanStatus.DEFAULTED);
        assertThat(l.getRemainingAmount()).isEqualTo(12_000);
        assertThat(l.isBlocksNewCredit()).isTrue();

        loans.processDueInstallments(sg); // still no money (and the snapshot is stale)
        assertThat(withReason("CREDIT_CALLBACK")).hasSize(1);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(1));
        fx.snapshot(sg, 20_000);
        loans.processDueInstallments(sg);
        assertThat(withReason("CREDIT_CALLBACK")).hasSize(2);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.CALLED);
        assertThat(l.getRemainingAmount()).isZero();
    }

    @Test
    void refusedSalaryStaysDueAndMarksTheEmployeeOverdue() {
        fx.snapshot(sg, 1_000_000);
        JobPosting p = hiring.createPosting(sg, JobRole.MECHANIC);
        Employee e = hiring.hire(sg, p.getId(), hiring.applications(sg, p.getId()).get(0).getId());
        long due = e.getNextSalaryDueGameTime();
        sg.setCurrentGameTime(due);
        payroll.paySalaries(sg);
        assertThat(e.getNextSalaryDueGameTime()).isGreaterThan(due);
        double fairness = e.getPayFairness();

        refuse(withReason("SALARY_PAYMENT").getFirst());

        assertThat(e.getNextSalaryDueGameTime()).isEqualTo(due);
        assertThat(e.isSalaryOverdue()).isTrue();
        assertThat(e.getPayFairness()).isLessThan(fairness);
    }

    @Test
    void refusedFarmlandPurchaseGivesTheFieldBackToTheSeller() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        Character seller = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        seller.setNegotiationTrait(NegotiationTrait.NEUTRAL);
        seller.setSellWilling(true);
        fx.snapshot(sg, 1_000_000);
        ownership.reconcile(sg);
        ownership.setOwner(sg, 13, OwnerType.CHARACTER, seller);
        Negotiation n = negotiations.startDirect(sg, seller.getId(), 13);
        negotiations.placeOffer(sg, n.getId(), 100_000);
        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.ACCEPTED);
        List<OutboxInstruction> batch = outbox.findBySavegameOrderByIdAsc(sg);

        batch.forEach(this::refuse);

        assertThat(n.getStatus()).isEqualTo(NegotiationStatus.FAILED);
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.CHARACTER);
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerCharacter()).isEqualTo(seller);
        assertThat(notices.open(sg)).singleElement()
                .satisfies(x -> assertThat(notices.details(x)).containsEntry("type", "FARMLAND_TRANSFER"));
    }

    /** Roadmap V3 (R3-Q1): a goods deal as the features will send it (instruction + money in one batch). */
    private OutboxInstruction v3Instruction(InstructionType type, String payload, String batchId) {
        OutboxInstruction o = new OutboxInstruction();
        o.setSavegame(sg);
        o.setInstructionId(de.farmpulse.rpsim.bridge.OutboxService.newInstructionId());
        o.setBatchId(batchId);
        o.setType(type);
        o.setPayloadJson(payload);
        o.setStatus(InstructionStatus.PENDING);
        o.setCreatedAtGameTime(sg.getCurrentGameTime());
        o.setCreatedAt(java.time.Instant.now());
        return outbox.save(o);
    }

    private void refuse(OutboxInstruction ins, String status, String message) {
        ins.setStatus("REJECTED".equals(status) ? InstructionStatus.REJECTED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status,
                ins.getRelatedEntityType(), ins.getRelatedEntityId()));
    }

    @Test
    void anOlderModRefusingARoadmapV3TypeAsksForAModUpdate() {
        OutboxInstruction transfer = v3Instruction(InstructionType.STORAGE_TRANSFER,
                "{\"direction\":\"IN\",\"fillType\":\"WHEAT\",\"amount\":8000}", "batch_goods");
        OutboxInstruction money = v3Instruction(InstructionType.MONEY_TRANSACTION,
                "{\"amount\":-1800,\"reason\":\"GOODS_PURCHASE\"}", "batch_goods");
        String why = transfer.getInstructionId() + ": unknown type STORAGE_TRANSFER";
        refuse(transfer, "REJECTED", why);
        refuse(money, "REJECTED", why);

        // one notice for the deal: the money part is reported together with its instruction
        assertThat(notices.open(sg)).singleElement().satisfies(n -> {
            assertThat(n.getKind()).isEqualTo(NoticeKind.INSTRUCTION_FAILED);
            assertThat(notices.details(n)).containsEntry("type", "STORAGE_TRANSFER").containsEntry("modOutdated", true)
                    .containsEntry("fillType", "WHEAT").containsEntry("direction", "IN");
        });
    }

    @Test
    void notSupportedCountsAsOutdatedButOtherFailuresDoNot() {
        assertThat(FailedInstructionService.modOutdated(InstructionType.VEHICLE_SPAWN, "NOT_SUPPORTED")).isTrue();
        assertThat(FailedInstructionService.modOutdated(InstructionType.MISSION_CREATE, "m: unknown type MISSION_CREATE"))
                .isTrue();
        assertThat(FailedInstructionService.modOutdated(InstructionType.VEHICLE_REMOVE, "VEHICLE_IN_USE")).isFalse();
        assertThat(FailedInstructionService.modOutdated(InstructionType.REPAIR_VEHICLE, "NOT_SUPPORTED")).isFalse();
        assertThat(FailedInstructionService.modOutdated(InstructionType.STORAGE_TRANSFER, null)).isFalse();

        OutboxInstruction remove = v3Instruction(InstructionType.VEHICLE_REMOVE, "{\"vehicleId\":\"veh_1\"}", null);
        refuse(remove, "FAILED", "VEHICLE_IN_USE");
        assertThat(notices.open(sg)).singleElement().satisfies(n -> assertThat(notices.details(n))
                .containsEntry("modOutdated", false).containsEntry("vehicleId", "veh_1"));
    }
}
