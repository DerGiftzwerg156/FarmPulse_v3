package de.farmpulse.rpsim.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TODO T-08: game month = FS25 period, anchored on the exported calendar. */
class CalendarServiceTest {

    static final long DAY = GameTime.MS_PER_DAY;

    GameTime gameTime = new GameTime();
    List<Object> events = new ArrayList<>();
    CalendarService calendar = new CalendarService(gameTime, events::add);
    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = TestData.activeSavegame("sg_cal");
        sg.setId(1L);
    }

    private static BridgeDtos.Calendar cal(int period, int dayInPeriod, int daysPerPeriod, int year, long monotonicDay) {
        return new BridgeDtos.Calendar(period, dayInPeriod, daysPerPeriod, year, monotonicDay, null);
    }

    @Test
    void fallbackWithoutCalendarIsOneDayPerPeriod() {
        assertThat(gameTime.msPerMonth(sg)).isEqualTo(DAY);
        assertThat(gameTime.addMonths(sg, 10 * DAY + 5, 1)).isEqualTo(11 * DAY);
        assertThat(gameTime.periodOfYear(sg, 0)).isEqualTo(1);
        assertThat(gameTime.periodOfYear(sg, 13 * DAY)).isEqualTo(2);
    }

    @Test
    void periodStartAndLengthComeFromTheExport() {
        // monotonic day 40 is day 2 of period 8 (October), 3 days per period
        calendar.update(sg, cal(8, 2, 3, 2, 40));
        assertThat(sg.getCalMonthStartGameTime()).isEqualTo(39 * DAY);
        assertThat(gameTime.msPerMonth(sg)).isEqualTo(3 * DAY);
        assertThat(gameTime.addMonths(sg, 40 * DAY + 3600, 1)).isEqualTo(42 * DAY);
        assertThat(gameTime.periodOfYear(sg, 42 * DAY)).isEqualTo(9);
        assertThat(gameTime.periodOfYear(sg, 39 * DAY + 3 * 5 * DAY)).isEqualTo(1); // 5 periods later: March
        assertThat(gameTime.isMonthStart(sg, 45 * DAY)).isTrue();
        assertThat(gameTime.isMonthStart(sg, 46 * DAY)).isFalse();
        assertThat(sg.getCalYear()).isEqualTo(2);
    }

    @Test
    void monthCounterContinuesOverPeriodChanges() {
        calendar.update(sg, cal(8, 1, 3, 2, 39));
        long index = sg.getCalMonthIndex();
        calendar.update(sg, cal(8, 3, 3, 2, 41));
        assertThat(sg.getCalMonthIndex()).isEqualTo(index);
        calendar.update(sg, cal(10, 1, 3, 2, 45)); // slept over two periods
        assertThat(sg.getCalMonthIndex()).isEqualTo(index + 2);
        calendar.update(sg, cal(8, 1, 3, 2, 39)); // older save loaded
        assertThat(sg.getCalMonthIndex()).isEqualTo(index);
    }

    @Test
    void changingDaysPerPeriodKeepsTheMonthOfScheduledDates() {
        calendar.update(sg, cal(8, 1, 3, 2, 39));
        events.clear();
        long index = sg.getCalMonthIndex();
        long due = gameTime.addMonths(sg, 39 * DAY, 2); // start of period 10: day 45
        assertThat(due).isEqualTo(45 * DAY);
        // the player switches to 5 days per period on day 40 (day 2 of period 8)
        calendar.update(sg, cal(8, 2, 5, 2, 40));
        assertThat(sg.getCalMonthIndex()).isEqualTo(index);
        assertThat(events).singleElement().isInstanceOf(CalendarChangedEvent.class);
        CalendarChangedEvent e = (CalendarChangedEvent) events.getFirst();
        assertThat(e.remap(due)).isEqualTo(39 * DAY + 10 * DAY);
        assertThat(gameTime.periodOfYear(sg, e.remap(due))).isEqualTo(10);
    }

    @Test
    void firstCalendarReplacesTheFallbackAndReschedules() {
        long dueUnderFallback = gameTime.addMonths(sg, 39 * DAY, 3); // fallback: day 42
        calendar.update(sg, cal(8, 1, 3, 2, 39));
        CalendarChangedEvent e = (CalendarChangedEvent) events.getFirst();
        assertThat(e.previous()).isEqualTo(GameTime.Anchor.FALLBACK);
        // the month count continues: the date stays 3 months after day 39 -> day 48
        assertThat(e.remap(dueUnderFallback)).isEqualTo(48 * DAY);
    }
}
