package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class LoanLifecycleTest {

    @Autowired Fixtures fx;
    @Autowired LoanService loans;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
    }

    @AfterEach
    void restore() {
        props.getFormulas().getCredit().setFinalStage("CALLBACK");
    }

    private List<String> reasons() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().map(OutboxInstruction::getPayloadJson).toList();
    }

    private List<String> jobTypes() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private void advanceDays(double days) {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(days));
        loans.processDueInstallments(sg);
    }

    @Test
    void disbursementAndOnTimeInstallments() {
        fx.snapshot(sg, 1_000_000);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Traktor", false, null);
        assertThat(reasons()).anyMatch(p -> p.contains("CREDIT_DISBURSEMENT") && p.contains("12000"));
        assertThat(l.getMonthlyInstallment()).isEqualTo(1000);
        advanceDays(1); // one game month (default 1 day)
        assertThat(l.getPaidInstallments()).isEqualTo(1);
        assertThat(l.getRemainingAmount()).isEqualTo(11_000);
        assertThat(reasons()).anyMatch(p -> p.contains("CREDIT_INSTALLMENT") && p.contains("-1000"));
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank))
                .extracting(e -> e.getReason()).containsExactly(TrustReason.ON_TIME_PAYMENT);
        for (int i = 0; i < 11; i++) {
            advanceDays(1);
        }
        assertThat(l.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(jobTypes()).contains("CREDIT_PAID_OFF");
    }

    @Test
    void legacyLoanHasNoDisbursement() {
        loans.create(sg, 50_000, 0.06, 60, "Altlast", true, null);
        assertThat(reasons()).noneMatch(p -> p.contains("CREDIT_DISBURSEMENT"));
    }

    @Test
    void completeEscalationChainStepByStepEndsWithCallback() {
        fx.snapshot(sg, 0); // no liquidity at all
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null); // no pending disbursement
        long dueAt = l.getNextDueGameTime();
        sg.setCurrentGameTime(dueAt);
        loans.processDueInstallments(sg);
        assertThat(l.getMissedInstallments()).isEqualTo(1);
        assertThat(l.getEscalationLevel()).isZero();
        assertThat(jobTypes()).doesNotContain("CREDIT_PAYMENT_REMINDER");

        sg.setCurrentGameTime(dueAt + GameTime.days(1));   // reminder after 1 day
        loans.processDueInstallments(sg);
        assertThat(l.getEscalationLevel()).isEqualTo(1);
        assertThat(jobTypes()).contains("CREDIT_PAYMENT_REMINDER");
        assertThat(reasons()).noneMatch(p -> p.contains("CREDIT_PENALTY"));

        sg.setCurrentGameTime(dueAt + GameTime.days(3));   // penalty after 3 days
        loans.processDueInstallments(sg);
        assertThat(l.getEscalationLevel()).isEqualTo(2);
        assertThat(reasons()).anyMatch(p -> p.contains("CREDIT_PENALTY") && p.contains("-50"));

        sg.setCurrentGameTime(dueAt + GameTime.days(5));   // trust loss after 5 days ...
        loans.processDueInstallments(sg);
        // ... and since >= 3 monthly installments are missed by now, the repeated default reaches the final stage
        assertThat(l.getEscalationLevel()).isEqualTo(4);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank)).extracting(e -> e.getReason())
                .contains(TrustReason.PAYMENT_ESCALATION);
        assertThat(l.getMissedInstallments()).isGreaterThanOrEqualTo(3); // monthly due dates kept passing
        assertThat(l.getStatus()).isEqualTo(LoanStatus.CALLED);            // repeated default -> call-back
        assertThat(reasons()).anyMatch(p -> p.contains("CREDIT_CALLBACK") && p.contains("-12000"));
        assertThat(publicActions.findBySavegameOrderByGameTimeAsc(sg)).extracting(e -> e.getType())
                .containsExactly(PublicActionType.PUBLIC_DEFAULT);
        assertThat(loans.history(l)).extracting(p -> p.getType()).contains(LoanPaymentType.MISSED,
                LoanPaymentType.PENALTY, LoanPaymentType.CALLBACK);
        assertThat(l.isBlocksNewCredit()).isTrue();
    }

    @Test
    void finalStageCanBlockInsteadOfCallBack() {
        props.getFormulas().getCredit().setFinalStage("BLOCK");
        fx.snapshot(sg, 0);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null); // no pending disbursement
        sg.setCurrentGameTime(l.getNextDueGameTime() + GameTime.days(5));
        loans.processDueInstallments(sg);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(l.isBlocksNewCredit()).isTrue();
        assertThat(jobTypes()).contains("CREDIT_BLOCKED");
        assertThat(reasons()).noneMatch(p -> p.contains("CREDIT_CALLBACK"));
    }

    @Test
    void catchingUpEndsOverduePhase() {
        fx.snapshot(sg, 0);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null); // no pending disbursement
        sg.setCurrentGameTime(l.getNextDueGameTime());
        loans.processDueInstallments(sg);
        assertThat(l.getOverdueSinceGameTime()).isNotNull();
        fx.snapshot(sg, 100_000);
        loans.processDueInstallments(sg);
        assertThat(l.getOverdueSinceGameTime()).isNull();
        assertThat(l.getPaidInstallments()).isEqualTo(1);
    }

    @Test
    void deferralGrantedOnceThenDenied() {
        fx.snapshot(sg, 1_000_000);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Mähdrescher", false, null);
        LoanService.DeferralResult first = loans.requestDeferral(sg, l.getId(), "Bitte um Aufschub");
        assertThat(first.granted()).isTrue();
        assertThat(l.getDeferredUntilGameTime()).isEqualTo(sg.getCurrentGameTime() + 2 * GameTime.days(1));
        advanceDays(1);
        assertThat(l.getPaidInstallments()).isZero(); // paused
        LoanService.DeferralResult second = loans.requestDeferral(sg, l.getId(), null);
        assertThat(second.granted()).isFalse();
        assertThat(second.reasonCategory()).isEqualTo("DEFERRAL_LIMIT_REACHED");
        assertThat(jobTypes()).contains("CREDIT_DEFERRAL_GRANTED", "CREDIT_DEFERRAL_DENIED");
    }

    @Test
    void deferralDeniedWhenEscalationTooAdvanced() {
        fx.snapshot(sg, 0);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null); // no pending disbursement
        sg.setCurrentGameTime(l.getNextDueGameTime() + GameTime.days(3) + 1);
        loans.processDueInstallments(sg);
        assertThat(loans.requestDeferral(sg, l.getId(), null).reasonCategory()).isEqualTo("ESCALATION_TOO_ADVANCED");
    }
}
