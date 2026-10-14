package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.repository.SatisfactionEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Owner request 2026-10-14: days off and trainings follow the month length like the sick leave. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class AbsenceScaleTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired SatisfactionService satisfaction;
    @Autowired TrainingService training;
    @Autowired SatisfactionEventRepository events;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000);
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalPeriod(1);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setAbsenceScaling(new RpsimProperties.AbsenceScaling());
        props.getFormulas().setTraining(new RpsimProperties.Trainings());
    }

    private Employee employee(JobRole role) {
        var c = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Klaus Berger");
        return hiring.createEmployee(sg, c, role, 70, 2400);
    }

    private double lastWorkloadDelta(Employee e) {
        return events.findByEmployeeOrderByGameTimeAscIdAsc(e).stream()
                .filter(ev -> ev.getCategory() == SatisfactionCategory.WORKLOAD).toList().getLast().getDelta();
    }

    @Test
    void gameDaysFollowTheMonthLength() {
        RpsimProperties.AbsenceScaling cfg = props.getFormulas().getAbsenceScaling();
        assertThat(AbsenceScale.gameDays(7, 30, cfg)).isEqualTo(7);
        assertThat(AbsenceScale.gameDays(7, 28, cfg)).isEqualTo(7);
        assertThat(AbsenceScale.gameDays(7, 2, cfg)).isEqualTo(1);
        assertThat(AbsenceScale.gameDays(30, 2, cfg)).isEqualTo(2);
        assertThat(AbsenceScale.gameDays(1, 1, cfg)).isEqualTo(1);
        cfg.setEnabled(false);
        assertThat(AbsenceScale.gameDays(7, 2, cfg)).isEqualTo(7);
    }

    @Test
    void daysOffAreScaledAndRelieveByTheShareOfTheMonth() {
        Employee e = employee(JobRole.MECHANIC);
        e.setWorkload(0);
        sg.setCalDaysPerPeriod(30);
        satisfaction.timeOff(e, 7);
        assertThat(e.getTimeOffUntilGameTime() - sg.getCurrentGameTime()).isEqualTo(7 * DAY);
        assertThat(lastWorkloadDelta(e)).isEqualTo(7 * 15.0);

        Employee f = employee(JobRole.MECHANIC);
        sg.setCalDaysPerPeriod(2);
        satisfaction.timeOff(f, 7); // a week of a 30-day month: one game day, i.e. half a month
        assertThat(f.getTimeOffUntilGameTime() - sg.getCurrentGameTime()).isEqualTo(DAY);
        assertThat(lastWorkloadDelta(f)).isEqualTo(15 * 15.0);
    }

    @Test
    void theTrainingIsScaledToo() {
        props.getFormulas().getTraining().setDurationDays(3);
        sg.setCalDaysPerPeriod(30);
        assertThat(training.durationGameDays(sg)).isEqualTo(3);
        sg.setCalDaysPerPeriod(2);
        assertThat(training.durationGameDays(sg)).isEqualTo(1);

        Employee e = employee(JobRole.MACHINE_OPERATOR);
        training.book(e, Training.TRUCK);
        assertThat(e.getTrainingUntilGameTime() - e.getTrainingFromGameTime()).isEqualTo(DAY);

        // the player switches to 30 days per period: the booked training lasts 3 days from its start
        sg.setCalDaysPerPeriod(30);
        training.onCalendarChanged(new CalendarChangedEvent(sg.getId(), new GameTime.Anchor(0, 0, 2, 1),
                new GameTime.Anchor(0, 0, 30, 1)));
        assertThat(e.getTrainingUntilGameTime() - e.getTrainingFromGameTime()).isEqualTo(3 * DAY);
    }

    @Test
    void runningDaysOffAreRescaledWhenTheDaysPerPeriodChange() {
        sg.setCalDaysPerPeriod(30);
        Employee e = employee(JobRole.MECHANIC);
        satisfaction.timeOff(e, 10);
        long now = sg.getCurrentGameTime();

        sg.setCalDaysPerPeriod(3);
        satisfaction.onCalendarChanged(new CalendarChangedEvent(sg.getId(), new GameTime.Anchor(0, 0, 30, 1),
                new GameTime.Anchor(0, 0, 3, 1)));
        assertThat(e.getTimeOffUntilGameTime() - now).isEqualTo(DAY); // a third of a month -> one of three days

        sg.setCalDaysPerPeriod(2);
        satisfaction.onCalendarChanged(new CalendarChangedEvent(sg.getId(), new GameTime.Anchor(0, 0, 3, 1),
                new GameTime.Anchor(0, 0, 2, 1)));
        assertThat(e.getTimeOffUntilGameTime() - now).isEqualTo(DAY); // at least one game day

        sg.setCalDaysPerPeriod(28);
        satisfaction.onCalendarChanged(new CalendarChangedEvent(sg.getId(), new GameTime.Anchor(0, 0, 2, 1),
                new GameTime.Anchor(0, 0, 28, 1)));
        assertThat(e.getTimeOffUntilGameTime() - now).isEqualTo(14 * DAY); // half a month again
    }
}
