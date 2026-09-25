package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobApplicationStatus;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SatisfactionEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class EmployeeSystemTest {

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired SatisfactionService satisfaction;
    @Autowired PayrollScheduler payroll;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired SatisfactionEventRepository satisfactionEvents;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000);
    }

    private List<String> jobTypes() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void postingGeneratesThreeToFiveCandidatesWithApplications() {
        for (int i = 0; i < 10; i++) {
            JobPosting p = hiring.createPosting(sg, JobRole.MECHANIC);
            List<JobApplication> apps = hiring.applications(sg, p.getId());
            assertThat(apps).hasSizeBetween(3, 5);
            apps.forEach(a -> {
                assertThat(a.getSkill()).isBetween(30, 95);
                assertThat(a.getExpectedSalary()).isPositive();
            });
        }
        long applicationsNarrated = jobTypes().stream().filter("JOB_APPLICATION"::equals).count();
        assertThat(applicationsNarrated).isBetween(30L, 50L);
    }

    @Test
    void salaryFollowsSkillDeterministically() {
        var low = hiring.rollOffer(JobRole.MECHANIC, de.farmpulse.rpsim.common.RandomSource.seeded(1));
        var same = hiring.rollOffer(JobRole.MECHANIC, de.farmpulse.rpsim.common.RandomSource.seeded(1));
        assertThat(low).isEqualTo(same);
    }

    @Test
    void interviewKeepsSkillAndSalaryFixed() {
        JobPosting p = hiring.createPosting(sg, JobRole.ANIMAL_KEEPER);
        JobApplication a = hiring.applications(sg, p.getId()).get(0);
        int skill = a.getSkill();
        long salary = a.getExpectedSalary();
        NarrationJob j = hiring.interviewQuestion(sg, p.getId(), a.getId(), "Ich zahle dir das Doppelte, ok?", Channel.CALL);
        assertThat(j.getPlayerMessage()).contains("Doppelte");
        assertThat(j.getChannel()).isEqualTo(Channel.CALL);
        assertThat(a.getSkill()).isEqualTo(skill);
        assertThat(a.getExpectedSalary()).isEqualTo(salary);
    }

    @Test
    void hiringRejectsOthersAndStartsWithNeutralSatisfaction() {
        JobPosting p = hiring.createPosting(sg, JobRole.MACHINE_OPERATOR);
        List<JobApplication> apps = hiring.applications(sg, p.getId());
        Employee e = hiring.hire(sg, p.getId(), apps.get(0).getId());
        assertThat(e.getPayFairness()).isEqualTo(70);
        assertThat(e.getWorkload()).isEqualTo(70);
        assertThat(e.getAppreciation()).isEqualTo(70);
        assertThat(hiring.applications(sg, p.getId()).stream().skip(1))
                .allMatch(a -> a.getStatus() == JobApplicationStatus.REJECTED
                        && a.getCharacter().getTerminationReason() == TerminationReason.REJECTED_APPLICANT);
        assertThat(jobTypes()).contains("EMPLOYEE_WELCOME", "APPLICATION_REJECTED");
    }

    private Employee employee() {
        JobPosting p = hiring.createPosting(sg, JobRole.MECHANIC);
        return hiring.hire(sg, p.getId(), hiring.applications(sg, p.getId()).get(0).getId());
    }

    @Test
    void resignationEscalationWarnsAt14AndResignsAt30Days() {
        Employee e = employee();
        e.setPayFairness(0);
        e.setWorkload(0);
        e.setAppreciation(0); // score = 0.25 * (0 + 0 + 0 + 82) = 20.5 < 30
        long start = sg.getCurrentGameTime();
        satisfaction.checkEscalation(e);
        assertThat(e.getLowSatisfactionSinceGameTime()).isEqualTo(start);
        sg.setCurrentGameTime(start + GameTime.days(13.9));
        satisfaction.checkEscalation(e);
        assertThat(e.isWarningSent()).isFalse();
        sg.setCurrentGameTime(start + GameTime.days(14));
        satisfaction.checkEscalation(e);
        assertThat(e.isWarningSent()).isTrue();
        assertThat(jobTypes()).contains("EMPLOYEE_WARNING");
        sg.setCurrentGameTime(start + GameTime.days(29.9));
        satisfaction.checkEscalation(e);
        assertThat(e.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        sg.setCurrentGameTime(start + GameTime.days(30));
        satisfaction.checkEscalation(e);
        assertThat(e.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(e.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        assertThat(jobTypes()).contains("EMPLOYEE_RESIGNATION");
    }

    /** AP-9.1: "< 30 points" is strict - exactly 30 is not low, 29.9 is. */
    @Test
    void warningThresholdIsStrict() {
        Employee e = employee();
        e.setWorkload(0);
        e.setAppreciation(0);
        e.setPayFairness(38); // 0.25 * (38 + 0 + 0 + 82) = 30.0
        satisfaction.checkEscalation(e);
        assertThat(satisfaction.needs(e).score()).isEqualTo(30.0);
        assertThat(e.getLowSatisfactionSinceGameTime()).isNull();
        e.setPayFairness(37.6); // 29.9
        satisfaction.checkEscalation(e);
        assertThat(e.getLowSatisfactionSinceGameTime()).isEqualTo(sg.getCurrentGameTime());
    }

    @Test
    void recoveryResetsEscalation() {
        Employee e = employee();
        e.setPayFairness(0);
        e.setWorkload(0);
        e.setAppreciation(0);
        satisfaction.checkEscalation(e);
        satisfaction.raise(e, e.getMonthlySalary() * 2); // +30 pay fairness
        satisfaction.timeOff(e, 5);                       // +75 workload
        satisfaction.checkEscalation(e);
        assertThat(e.getLowSatisfactionSinceGameTime()).isNull();
    }

    @Test
    void salaryOverdueTriggersNegativePayFairnessEvent() {
        Employee e = employee();
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 0, de.farmpulse.rpsim.support.TestData.farmFacts(
                sg.getBridgeSavegameId(), sg.getCurrentGameTime() + 1, 0));
        sg.setCurrentGameTime(e.getNextSalaryDueGameTime());
        payroll.paySalaries(sg);
        assertThat(e.isSalaryOverdue()).isTrue();
        assertThat(satisfactionEvents.findByEmployeeOrderByGameTimeAscIdAsc(e)).anyMatch(ev ->
                ev.getCategory() == SatisfactionCategory.PAY_FAIRNESS && ev.getDelta() < 0);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).noneMatch(o -> o.getPayloadJson().contains("SALARY_PAYMENT"));
        payroll.paySalaries(sg); // no duplicate event while still overdue
        assertThat(satisfactionEvents.findByEmployeeOrderByGameTimeAscIdAsc(e).stream()
                .filter(ev -> ev.getDelta() < 0).count()).isEqualTo(1);
        fx.snapshot(sg, sg.getCurrentGameTime() + 2, 1_000_000, de.farmpulse.rpsim.support.TestData.farmFacts(
                sg.getBridgeSavegameId(), sg.getCurrentGameTime() + 2, 1_000_000));
        payroll.paySalaries(sg);
        assertThat(e.isSalaryOverdue()).isFalse();
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("SALARY_PAYMENT"));
    }

    @Test
    void monthlyEffectInstructionReflectsSatisfaction() {
        Employee e = employee();
        e.setPayFairness(0);
        e.setWorkload(0);
        e.setAppreciation(0);
        long amount = satisfaction.bookMonthlyEffect(e);
        assertThat(amount).isEqualTo(-750); // multiplier clamped to 0.5
        List<OutboxInstruction> ins = outbox.findBySavegameOrderByIdAsc(sg);
        assertThat(ins).anyMatch(o -> o.getPayloadJson().contains("EMPLOYEE_EFFECT") && o.getPayloadJson().contains("-750"));
    }

    @Test
    void conversationHasCooldown() {
        Employee e = employee();
        assertThat(satisfaction.conversation(e)).isTrue();
        assertThat(satisfaction.conversation(e)).isFalse();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1));
        assertThat(satisfaction.conversation(e)).isTrue();
    }

    @Test
    void dismissal() {
        Employee e = employee();
        hiring.dismiss(sg, e.getId());
        assertThat(e.getCharacter().getTerminationReason()).isEqualTo(TerminationReason.DISMISSED);
    }
}
