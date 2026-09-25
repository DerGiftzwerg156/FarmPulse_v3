package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.CreditReasonCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class CreditApplicationServiceTest {

    @Autowired Fixtures fx;
    @Autowired CreditApplicationService service;
    @Autowired LoanService loanService;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired LoanRepository loans;
    @Autowired EmployeeRepository employees;
    @Autowired FactsServiceProbe probe;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.bank(sg);
    }

    @Test
    void decisionIsComputedImmediatelyButOnlyVisibleAfterProcessingTime() {
        fx.snapshot(sg, 1_000_000);
        CreditApplication a = service.submit(sg, 50_000, "Neuer Traktor", 60);
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PROCESSING);
        assertThat(a.getDecision()).isNotNull();
        long delay = a.getDecisionVisibleAtGameTime() - a.getSubmittedAtGameTime();
        assertThat(delay).isBetween(GameTime.days(1), GameTime.days(2));

        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime() - 1);
        service.releaseVisibleDecisions(sg);
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PROCESSING);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).isEmpty();

        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        service.releaseVisibleDecisions(sg);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).hasSize(1);
    }

    @Test
    void wealthyFarmIsApprovedAndDisbursed() {
        fx.snapshot(sg, 1_000_000);
        CreditApplication a = service.submit(sg, 50_000, "Neuer Traktor", 60);
        assertThat(a.getDecision()).isEqualTo(CreditDecision.APPROVED);
        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        service.releaseVisibleDecisions(sg);
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.ACCEPTED);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).get(0).getEventType()).isEqualTo("CREDIT_APPROVED");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).get(0).getFactsJson()).doesNotContainIgnoringCase("score");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("CREDIT_DISBURSEMENT"));
    }

    @Test
    void absurdRequestIsRejectedWithCategoryOnly() {
        fx.snapshot(sg, 1_000);
        CreditApplication a = service.submit(sg, 50_000_000, "Weltherrschaft", 60);
        assertThat(a.getDecision()).isEqualTo(CreditDecision.REJECTED);
        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        service.releaseVisibleDecisions(sg);
        String facts = jobs.findBySavegameOrderByIdAsc(sg).get(0).getFactsJson();
        assertThat(facts).contains("reasonCategory").doesNotContainIgnoringCase("score");
        assertThat(loans.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }

    @Test
    void counterOfferCanBeAccepted() {
        fx.snapshot(sg, 1_000);
        // standard farm assets ~ 580k, vanilla loan 80k: a large request lands in the counter band
        CreditApplication a = null;
        for (long amount = 50_000; amount <= 2_000_000; amount += 25_000) {
            CreditApplication candidate = service.submit(sg, amount, "Halle", 60);
            if (candidate.getDecision() == CreditDecision.COUNTER_OFFER) {
                a = candidate;
                break;
            }
        }
        assertThat(a).as("some amount must produce a counter offer").isNotNull();
        assertThat(a.getOfferedAmount()).isLessThanOrEqualTo(a.getAmount());
        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        service.releaseVisibleDecisions(sg);
        service.acceptCounterOffer(sg, a.getId());
        Loan loan = loans.findById(a.getLoanId()).orElseThrow();
        assertThat(loan.getPrincipal()).isEqualTo(a.getOfferedAmount());
        Long id = a.getId();
        assertThatThrownBy(() -> service.acceptCounterOffer(sg, id)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void blockedSavegameIsRejectedWithCreditBlocked() {
        fx.snapshot(sg, 1_000_000);
        Loan l = loanService.create(sg, 1000, 0.05, 12, "alt", true, null);
        l.setBlocksNewCredit(true);
        CreditApplication a = service.submit(sg, 1_000, "Kleinkredit", 12);
        assertThat(a.getDecision()).isEqualTo(CreditDecision.REJECTED);
        assertThat(a.getReasonCategory()).isEqualTo(CreditReasonCategory.CREDIT_BLOCKED);
    }

    @Test
    void invalidTermIsRejectedByForm() {
        assertThatThrownBy(() -> service.submit(sg, 1000, "x", 1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.submit(sg, -5, "x", 12)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void officeClerkShortensProcessingSlightly() {
        var cfg = new de.farmpulse.rpsim.config.RpsimProperties().getFormulas().getCredit();
        cfg.setProcessingDaysMin(1);
        cfg.setProcessingDaysMax(1);
        long without = service.processingTime(sg, cfg);
        var clerkChar = fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, "Petra");
        Employee e = new Employee();
        e.setSavegame(sg);
        e.setCharacter(clerkChar);
        e.setJobRole(JobRole.OFFICE_CLERK);
        e.setSkill(100);
        e.setMonthlySalary(2000);
        e.setStatus(EmployeeStatus.ACTIVE);
        employees.save(e);
        long with = service.processingTime(sg, cfg);
        assertThat(without - with).isEqualTo(GameTime.hours(6));
    }

    @Test
    void hardToneUsesStricterBank() {
        fx.snapshot(sg, 1_000_000);
        probe.assertStricter(sg);
        sg.setTonePreset(TonePreset.HARSH);
        probe.assertStricter(sg);
    }
}
