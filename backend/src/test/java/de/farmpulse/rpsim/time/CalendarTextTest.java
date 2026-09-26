package de.farmpulse.rpsim.time;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.domain.Savegame;
import org.junit.jupiter.api.Test;

/** TODO T-21: calendar reference for the narration. */
class CalendarTextTest {

    private static Savegame sg(Integer period, int day, int days, Integer year, String season) {
        Savegame s = new Savegame();
        s.setCalPeriod(period);
        s.setCalDayInPeriod(day);
        s.setCalDaysPerPeriod(days);
        s.setCalYear(year);
        s.setCalSeason(season);
        return s;
    }

    @Test
    void periodOneIsMarch() {
        assertThat(CalendarText.month(1)).isEqualTo("März");
        assertThat(CalendarText.month(8)).isEqualTo("Oktober");
        assertThat(CalendarText.month(12)).isEqualTo("Februar");
    }

    @Test
    void phaseSeasonAndYear() {
        assertThat(CalendarText.describe(sg(8, 3, 3, 2, "AUTUMN"))).isEqualTo("Ende Oktober, Herbst, Jahr 2");
        assertThat(CalendarText.describe(sg(8, 1, 3, 2, null))).isEqualTo("Anfang Oktober, Jahr 2");
        assertThat(CalendarText.describe(sg(8, 2, 3, null, "WINTER"))).isEqualTo("Mitte Oktober, Winter");
        assertThat(CalendarText.describe(sg(10, 1, 1, 1, "SOMETHING"))).isEqualTo("Dezember, Jahr 1");
        assertThat(CalendarText.describe(sg(null, 1, 1, 1, null))).isNull();
    }
}
