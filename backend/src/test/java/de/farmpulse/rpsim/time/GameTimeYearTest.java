package de.farmpulse.rpsim.time;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.domain.Savegame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Logic review LR-M4: the FS25 year of a month, counted from the calendar anchor (year changes with period 1). */
class GameTimeYearTest {

    private final GameTime gameTime = new GameTime();

    /** Anchor: month 40 is period 11 (January) of year 3 and starts at day 40 x daysPerPeriod. */
    private static Savegame anchored(int daysPerPeriod) {
        Savegame sg = new Savegame();
        sg.setCalMonthIndex(40L);
        sg.setCalMonthStartGameTime(40L * daysPerPeriod * GameTime.MS_PER_DAY);
        sg.setCalDaysPerPeriod(daysPerPeriod);
        sg.setCalPeriod(11);
        sg.setCalYear(3);
        return sg;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void theYearChangesWithPeriodOne(int daysPerPeriod) {
        Savegame sg = anchored(daysPerPeriod);
        long month = daysPerPeriod * GameTime.MS_PER_DAY;
        long anchorStart = sg.getCalMonthStartGameTime();
        assertThat(gameTime.yearOf(sg, anchorStart, null)).isEqualTo(3);           // period 11
        assertThat(gameTime.yearOf(sg, anchorStart + month, null)).isEqualTo(3);   // period 12
        assertThat(gameTime.yearOf(sg, anchorStart + 2 * month, null)).isEqualTo(4); // period 1
        assertThat(gameTime.yearOf(sg, anchorStart - 10 * month, null)).isEqualTo(3); // period 1 of year 3
        assertThat(gameTime.yearOf(sg, anchorStart - 11 * month, null)).isEqualTo(2); // period 12 of year 2
        assertThat(gameTime.yearOf(sg, anchorStart + 14 * month, null)).isEqualTo(5); // period 1 two years on
        assertThat(gameTime.periodOfYear(sg, anchorStart + 2 * month)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {7})
    void withoutACalendarYearTheFallbackCounts(int fallback) {
        Savegame sg = anchored(1);
        sg.setCalYear(null);
        assertThat(gameTime.yearOf(sg, 0, fallback)).isEqualTo(fallback);
        assertThat(gameTime.yearOf(sg, 0, null)).isNull();
    }
}
