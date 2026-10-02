package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-A5: fixed-term seasonal workers for the harvest (owner decisions 2026-10-02). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class SeasonalWorkerTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired SeasonalWorkerService seasonal;
    @Autowired SatisfactionService satisfaction;
    @Autowired TrainingService trainings;
    @Autowired WorkforceService workforce;
    @Autowired PayrollScheduler payroll;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(8); // one day per month: day 10 is August (FS25 period 6), day 13 is November
        fx.snapshot(sg, 1_000_000);
    }

    private Employee seasonalWorker(int skill, String name) {
        Character c = fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, name);
        return hiring.createEmployee(sg, c, JobRole.SEASONAL_WORKER, skill, hiring.seasonalSalary(skill));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private void day() {
        seasonal.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void needs(Employee e, double value) {
        e.setPayFairness(value);
        e.setWorkload(value);
        e.setAppreciation(value);
        e.setNeedsUpdatedAtGameTime(sg.getCurrentGameTime());
    }

    @Test
    void postingsOnlyBeforeAndInTheHarvest() {
        sg.setCalPeriod(1); // day 10 = January (period 11)
        assertThatThrownBy(() -> hiring.createPosting(sg, JobRole.SEASONAL_WORKER))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Erntezeit");
        sg.setCalPeriod(8);
        JobPosting p = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        sg.setCalPeriod(11); // the season is over before the player chose: no hire
        Long app = hiring.applications(sg, p.getId()).getFirst().getId();
        assertThatThrownBy(() -> hiring.hire(sg, p.getId(), app)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Erntezeit");
    }

    @Test
    void applicantsHaveSkill30To70AndOperatorSalaryTimes125WithoutTraining() {
        assertThat(hiring.seasonalSalary(50)).isEqualTo(3000); // 2400 x 1.25
        assertThat(hiring.seasonalSalary(30)).isEqualTo(Math.round(2400 * (1 + 0.3 * -20 / 50.0) * 1.25 / 10.0) * 10);
        JobPosting p = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        List<JobApplication> apps = hiring.applications(sg, p.getId());
        assertThat(apps).isNotEmpty().allSatisfy(a -> {
            assertThat(a.getSkill()).isBetween(30, 70);
            assertThat(a.getExpectedSalary()).isEqualTo(hiring.seasonalSalary(a.getSkill()));
            assertThat(a.getTraining()).isNull();
            assertThat(a.getReturningEmployeeId()).isNull();
        });
        Employee w = hiring.hire(sg, p.getId(), apps.getFirst().getId());
        assertThat(w.getJobRole()).isEqualTo(JobRole.SEASONAL_WORKER);
        assertThat(w.getContractEndsAtGameTime()).isEqualTo(13 * DAY); // start of November = end of October
        // a driver without trainings in the roster, no salary negotiation, no trainings
        Map<String, Object> entry = workforce.roster(sg).stream().filter(m -> m.get("employeeId").equals(w.getId()))
                .findFirst().orElseThrow();
        assertThat(entry.get("role")).isEqualTo("SEASONAL_WORKER");
        assertThat((List<?>) entry.get("trainings")).isEmpty();
        assertThat(JobRole.SEASONAL_WORKER.drives()).isTrue();
        assertThatThrownBy(() -> satisfaction.raise(w, w.getMonthlySalary() + 500))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Gehaltsverhandlung");
        assertThatThrownBy(() -> trainings.book(w, Training.LARGE_TRACTOR)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void atMostThreeSeasonalWorkers() {
        seasonalWorker(40, "Anna Saison");
        seasonalWorker(40, "Ben Saison");
        JobPosting p = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        seasonalWorker(40, "Clara Saison");
        Long app = hiring.applications(sg, p.getId()).getFirst().getId();
        assertThatThrownBy(() -> hiring.hire(sg, p.getId(), app)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("3 Saisonkräfte");
        assertThatThrownBy(() -> hiring.createPosting(sg, JobRole.SEASONAL_WORKER))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("3 Saisonkräfte");
    }

    @Test
    void contractEndsAfterOctoberWithTheLastSalaryPaidAndAFarewell() {
        Employee w = seasonalWorker(50, "Anna Saison");
        sg.setCurrentGameTime(12 * DAY);
        day();
        assertThat(w.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);

        sg.setCurrentGameTime(13 * DAY); // November: the payroll runs ahead of the day events
        payroll.paySalaries(sg);
        day();
        assertThat(outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)).hasSize(3); // August to October
        assertThat(w.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(w.getTerminatedAtGameTime()).isEqualTo(13 * DAY);
        assertThat(w.getSeasonEndSatisfaction()).isNotNull();
        assertThat(w.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        assertThat(w.getCharacter().getTerminationReason()).isEqualTo(TerminationReason.CONTRACT_ENDED);
        assertThat(narrations()).contains("SEASONAL_WORKER_FAREWELL");
    }

    @Test
    void wellTreatedWorkersApplyAgainTheNextYear() {
        Employee good = seasonalWorker(55, "Anna Saison");
        Employee bad = seasonalWorker(45, "Ben Saison");
        sg.setCurrentGameTime(13 * DAY);
        needs(good, 100);
        needs(bad, 0);
        day();
        assertThat(good.getSeasonEndSatisfaction()).isGreaterThanOrEqualTo(60);
        assertThat(bad.getSeasonEndSatisfaction()).isLessThan(60);

        sg.setCurrentGameTime(22 * DAY); // August of the next year
        JobPosting p = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        List<JobApplication> returning = hiring.applications(sg, p.getId()).stream()
                .filter(a -> a.getReturningEmployeeId() != null).toList();
        assertThat(returning).hasSize(1);
        JobApplication a = returning.getFirst();
        assertThat(a.getCharacter().getId()).isEqualTo(good.getCharacter().getId());
        assertThat(a.getSkill()).isEqualTo(55);
        assertThat(a.getExpectedSalary()).isEqualTo(hiring.seasonalSalary(55));
        assertThat(narrations()).contains("SEASONAL_WORKER_RETURN");

        Employee back = hiring.hire(sg, p.getId(), a.getId());
        assertThat(back.getId()).isEqualTo(good.getId()); // the same employee record and character (trust stays)
        assertThat(back.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(back.getCharacter().getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        assertThat(back.getCharacter().getTerminationReason()).isNull();
        assertThat(back.getContractEndsAtGameTime()).isEqualTo(25 * DAY);
        assertThat(back.getNextSalaryDueGameTime()).isEqualTo(23 * DAY);

        // he applied this year: a second posting brings no second application
        JobPosting second = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        assertThat(hiring.applications(sg, second.getId())).allSatisfy(x -> assertThat(x.getReturningEmployeeId()).isNull());
    }

    @Test
    void onlyTheNextYear() {
        Employee good = seasonalWorker(55, "Anna Saison");
        sg.setCurrentGameTime(13 * DAY);
        needs(good, 100);
        day();
        sg.setCurrentGameTime(34 * DAY); // August two years later
        JobPosting p = hiring.createPosting(sg, JobRole.SEASONAL_WORKER);
        assertThat(hiring.applications(sg, p.getId())).allSatisfy(x -> assertThat(x.getReturningEmployeeId()).isNull());
    }
}
