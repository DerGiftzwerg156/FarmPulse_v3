package de.farmpulse.rpsim.time;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.domain.Savegame;

/**
 * TODO T-21: German description of the FS25 date for the narration ("Ende Oktober, Herbst, Jahr 2"), so characters
 * can refer to real months and seasons. Month from the FS25 period (1 = March, see T-08), season only when the game
 * exported it (name from its Season table), never derived.
 */
public final class CalendarText {

    static final List<String> MONTHS = List.of("März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober",
            "November", "Dezember", "Januar", "Februar");
    static final Map<String, String> SEASONS = Map.of("SPRING", "Frühling", "SUMMER", "Sommer", "AUTUMN", "Herbst",
            "FALL", "Herbst", "WINTER", "Winter");

    private CalendarText() {
    }

    /** Month name of an FS25 period (1 = March). */
    public static String month(int period) {
        return MONTHS.get(Math.floorMod(period - 1, GameTime.PERIODS_PER_YEAR));
    }

    /** "Anfang" / "Mitte" / "Ende" of the month, null when a month has only one day. */
    static String phase(int dayInPeriod, int daysPerPeriod) {
        if (daysPerPeriod <= 1) {
            return null;
        }
        double pos = (dayInPeriod - 0.5) / daysPerPeriod;
        return pos < 1 / 3.0 ? "Anfang" : pos < 2 / 3.0 ? "Mitte" : "Ende";
    }

    /** e.g. "Ende Oktober, Herbst, Jahr 2"; null before the first calendar export. */
    public static String describe(Savegame sg) {
        if (sg == null || sg.getCalPeriod() == null) {
            return null;
        }
        int days = sg.getCalDaysPerPeriod() == null ? 1 : sg.getCalDaysPerPeriod();
        int day = sg.getCalDayInPeriod() == null ? 1 : sg.getCalDayInPeriod();
        String phase = phase(day, days);
        StringBuilder b = new StringBuilder();
        if (phase != null) {
            b.append(phase).append(' ');
        }
        b.append(month(sg.getCalPeriod()));
        String season = sg.getCalSeason() == null ? null : SEASONS.get(sg.getCalSeason().toUpperCase());
        if (season != null) {
            b.append(", ").append(season);
        }
        if (sg.getCalYear() != null) {
            b.append(", Jahr ").append(sg.getCalYear());
        }
        return b.toString();
    }
}
