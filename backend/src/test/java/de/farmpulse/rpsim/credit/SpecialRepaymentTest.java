package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPayment;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Sondertilgung: shorter term, fee above the yearly free limit, pro-rata interest on full repayment, refusals. */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class SpecialRepaymentTest {

    @Autowired Fixtures fx;
    @Autowired LoanService loans;
    @Autowired GameTime gameTime;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
        fx.snapshot(sg, 1_000_000);
    }

    private List<OutboxInstruction> money() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    private List<String> jobTypes() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<TrustReason> trust() {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank).stream().map(e -> e.getReason()).toList();
    }

    private LoanPayment last(Loan l, LoanPaymentType type) {
        return loans.history(l).stream().filter(p -> p.getType() == type).reduce((a, b) -> b).orElseThrow();
    }

    private String refusal(Loan l, long amount) {
        try {
            loans.specialRepayment(sg, l.getId(), amount);
            return null;
        } catch (BusinessRuleException e) {
            return e.getCode();
        }
    }

    @Test
    void partialRepaymentKeepsTheInstallmentAndShortensTheTerm() {
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        LoanService.SpecialRepaymentResult r = loans.specialRepayment(sg, l.getId(), 1_000);

        assertThat(r.fee()).isZero();
        assertThat(r.interest()).isZero();
        assertThat(l.getRemainingAmount()).isEqualTo(11_000);
        assertThat(l.getMonthlyInstallment()).isEqualTo(1_000);
        assertThat(loans.remainingInstallments(l)).isEqualTo(11);
        assertThat(r.remainingInstallments()).isEqualTo(11);
        assertThat(money()).extracting(OutboxInstruction::getPayloadJson)
                .anyMatch(p -> p.contains("CREDIT_SPECIAL_REPAYMENT") && p.contains("-1000"))
                .noneMatch(p -> p.contains("CREDIT_PREPAYMENT_FEE"));
        assertThat(jobTypes()).contains("CREDIT_SPECIAL_REPAYMENT").doesNotContain("CREDIT_PAID_OFF");

        // the next regular installment counts against the shortened plan
        sg.setCurrentGameTime(l.getNextDueGameTime());
        loans.processDueInstallments(sg);
        assertThat(money()).extracting(OutboxInstruction::getPayloadJson).anyMatch(p -> p.contains("Kreditrate 1/11"));
        assertThat(loans.remainingInstallments(l)).isEqualTo(10);
    }

    @Test
    void feeIsChargedOnlyAboveTheYearlyFreeLimitAndResetsWithTheNextYear() {
        Loan l = loans.create(sg, 100_000, 0.05, 60, "Halle", true, null);
        assertThat(loans.specialRepaymentTerms(sg, l).freeAmountLeft()).isEqualTo(10_000);

        assertThat(loans.specialRepayment(sg, l.getId(), 6_000).fee()).isZero();
        assertThat(loans.specialRepaymentTerms(sg, l).freeAmountLeft()).isEqualTo(4_000);
        LoanService.SpecialRepaymentResult second = loans.specialRepayment(sg, l.getId(), 6_000);
        assertThat(second.fee()).isEqualTo(20); // 1 % of the 2,000 above the free limit
        assertThat(loans.specialRepaymentTerms(sg, l).freeAmountLeft()).isZero();

        // repayment and fee are one batch: the mod books both or none
        List<OutboxInstruction> last2 = money().subList(money().size() - 2, money().size());
        assertThat(last2.get(0).getPayloadJson()).contains("CREDIT_SPECIAL_REPAYMENT", "-6000");
        assertThat(last2.get(1).getPayloadJson()).contains("CREDIT_PREPAYMENT_FEE", "-20");
        assertThat(last2.get(0).getBatchId()).isNotNull().isEqualTo(last2.get(1).getBatchId());
        assertThat(last(l, LoanPaymentType.PREPAYMENT_FEE).getAmount()).isEqualTo(20);
        assertThat(l.getRemainingAmount()).isEqualTo(88_000); // the fee does not reduce the debt

        sg.setCurrentGameTime(gameTime.addMonths(sg, sg.getCurrentGameTime(), GameTime.PERIODS_PER_YEAR));
        assertThat(loans.specialRepaymentTerms(sg, l).freeAmountLeft()).isEqualTo(10_000);
    }

    @Test
    void fullRepaymentAddsProRataInterestAndSendsBothMails() {
        Loan l = loans.create(sg, 12_000, 0.12, 12, "Traktor", true, null); // 1 % interest per month = 120
        long monthStart = gameTime.addMonths(sg, l.getNextDueGameTime(), -1);
        sg.setCurrentGameTime(Math.max(monthStart, l.getStartedAtGameTime())
                + (l.getNextDueGameTime() - Math.max(monthStart, l.getStartedAtGameTime())) / 2);
        assertThat(loans.specialRepaymentTerms(sg, l).payoffInterest()).isEqualTo(60);

        LoanService.SpecialRepaymentResult r = loans.specialRepayment(sg, l.getId(), 12_000);

        assertThat(r.paidOff()).isTrue();
        assertThat(r.interest()).isEqualTo(60);
        assertThat(r.fee()).isEqualTo(108); // 1 % of 10,800 above the free 1,200
        assertThat(l.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(l.getRemainingAmount()).isZero();
        assertThat(last(l, LoanPaymentType.SPECIAL_REPAYMENT).getAmount()).isEqualTo(12_060);
        assertThat(last(l, LoanPaymentType.SPECIAL_REPAYMENT).getPrincipalPart()).isEqualTo(12_000);
        assertThat(money()).extracting(OutboxInstruction::getPayloadJson)
                .anyMatch(p -> p.contains("CREDIT_SPECIAL_REPAYMENT") && p.contains("-12060"))
                .anyMatch(p -> p.contains("CREDIT_PREPAYMENT_FEE") && p.contains("-108"));
        assertThat(jobTypes()).containsSubsequence("CREDIT_SPECIAL_REPAYMENT", "CREDIT_PAID_OFF");
        assertThat(refusal(l, 1)).isEqualTo("LOAN_NOT_ACTIVE");
    }

    @Test
    void trustBonusOnlyFromTheMinimumShareOfTheRemainingDebt() {
        Loan l = loans.create(sg, 100_000, 0.0, 100, "Hof", true, null);
        loans.specialRepayment(sg, l.getId(), 4_999); // below 5 % of 100,000
        assertThat(trust()).doesNotContain(TrustReason.SPECIAL_REPAYMENT);
        assertThat(last(l, LoanPaymentType.SPECIAL_REPAYMENT).getTrustBonusGiven()).isFalse();

        long share = Math.round(Math.ceil(l.getRemainingAmount() * 0.05)); // exactly 5 % of the remaining debt
        loans.specialRepayment(sg, l.getId(), share);
        assertThat(trust()).containsExactly(TrustReason.SPECIAL_REPAYMENT);
    }

    @Test
    void refusedWhenOverdueDeferredTooLargeOrNotCovered() {
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        assertThat(refusal(l, 0)).isEqualTo("INVALID_AMOUNT");
        assertThat(refusal(l, 12_001)).isEqualTo("INVALID_AMOUNT");

        fx.snapshot(sg, 500);
        assertThat(loans.specialRepaymentTerms(sg, l).refusal()).isNull();
        assertThat(refusal(l, 501)).isEqualTo("INSUFFICIENT_LIQUIDITY");
        assertThat(refusal(l, 500)).isNull();

        fx.snapshot(sg, 1_000_000);
        loans.requestDeferral(sg, l.getId(), null);
        assertThat(refusal(l, 100)).isEqualTo("LOAN_DEFERRED");

        Loan overdue = loans.create(sg, 12_000, 0.0, 12, "Scheune", true, null);
        fx.snapshot(sg, 0);
        sg.setCurrentGameTime(overdue.getNextDueGameTime());
        loans.processDueInstallments(sg);
        fx.snapshot(sg, 1_000_000);
        assertThat(loans.specialRepaymentTerms(sg, overdue).refusal()).isEqualTo("LOAN_OVERDUE");
        assertThat(refusal(overdue, 100)).isEqualTo("LOAN_OVERDUE");
    }

    @Test
    void failedBookingRestoresTheDebtAndTakesTheTrustBonusBack() {
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        loans.specialRepayment(sg, l.getId(), 12_000);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        LoanPayment repayment = last(l, LoanPaymentType.SPECIAL_REPAYMENT);
        LoanPayment fee = last(l, LoanPaymentType.PREPAYMENT_FEE);

        assertThat(loans.onBookingFailed(sg, l.getId(), fee.getInstructionId(), "INSUFFICIENT_FUNDS")).isTrue();
        assertThat(loans.onBookingFailed(sg, l.getId(), repayment.getInstructionId(), "INSUFFICIENT_FUNDS")).isTrue();

        assertThat(repayment.getType()).isEqualTo(LoanPaymentType.REVERSED);
        assertThat(fee.getType()).isEqualTo(LoanPaymentType.REVERSED);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(l.getRemainingAmount()).isEqualTo(12_000);
        assertThat(trust()).containsExactly(TrustReason.SPECIAL_REPAYMENT, TrustReason.SPECIAL_REPAYMENT_REVERSED);
        assertThat(loans.specialRepaymentTerms(sg, l).freeAmountLeft()).isEqualTo(1_200); // no longer counted
    }
}
