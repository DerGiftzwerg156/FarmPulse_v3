package de.farmpulse.rpsim.time;

import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.stereotype.Component;

/**
 * Game-time arithmetic. gameTime = in-game milliseconds since savegame start (see bridge protocol).
 * <p>
 * TODO T-08: the game month is the FS25 period. The calendar exported by the mod ({@code farm_facts.json} →
 * {@code calendar}) anchors a monotonic month counter on the savegame (see {@link CalendarService}); all month
 * arithmetic (installments, salaries, monthly effects, invitations) goes through this anchor. "Days per period" can be
 * changed by the player at any time: month starts are computed from the current anchor, scheduled dates are remapped
 * when it changes ({@link CalendarChangedEvent}). Without any calendar yet (legacy files) the FS25 default of one day
 * per period is used.
 */
@Component
public class GameTime {

    public static final long MS_PER_HOUR = 60L * 60 * 1000;
    public static final long MS_PER_DAY = 24 * MS_PER_HOUR;
    /** FS25 periods per year (Environment.PERIODS_IN_YEAR); period 1 is March. */
    public static final int PERIODS_PER_YEAR = 12;

    /**
     * Calendar anchor: month (= FS25 period) {@code monthIndex} starts at {@code monthStart} and is period
     * {@code period} (1..12) of its year; every month has {@code daysPerPeriod} days.
     */
    public record Anchor(long monthIndex, long monthStart, int daysPerPeriod, int period) {

        /** Fallback before the first calendar export: FS25 default, one day per period, month 0 starts at 0. */
        public static final Anchor FALLBACK = new Anchor(0, 0, 1, 1);

        public long msPerMonth() {
            return daysPerPeriod * MS_PER_DAY;
        }

        public long monthIndex(long gameTime) {
            return monthIndex + Math.floorDiv(gameTime - monthStart, msPerMonth());
        }

        public long monthStart(long index) {
            return monthStart + (index - monthIndex) * msPerMonth();
        }

        /** FS25 period (1..12) of the month with the given index. */
        public int periodOf(long index) {
            return (int) Math.floorMod(period - 1 + (index - monthIndex), (long) PERIODS_PER_YEAR) + 1;
        }
    }

    public static long days(double days) {
        return Math.round(days * MS_PER_DAY);
    }

    public static long hours(double hours) {
        return Math.round(hours * MS_PER_HOUR);
    }

    public static long dayIndex(long gameTime) {
        return Math.floorDiv(gameTime, MS_PER_DAY);
    }

    public static double toDays(long ms) {
        return ms / (double) MS_PER_DAY;
    }

    /** Current calendar anchor of the savegame. */
    public Anchor anchor(Savegame sg) {
        if (sg == null || sg.getCalMonthIndex() == null || sg.getCalMonthStartGameTime() == null
                || sg.getCalDaysPerPeriod() == null || sg.getCalPeriod() == null) {
            return Anchor.FALLBACK;
        }
        return new Anchor(sg.getCalMonthIndex(), sg.getCalMonthStartGameTime(), Math.max(1, sg.getCalDaysPerPeriod()),
                sg.getCalPeriod());
    }

    /** Length of a game month (= FS25 period) with the current "days per period" setting. */
    public long msPerMonth(Savegame sg) {
        return anchor(sg).msPerMonth();
    }

    public long monthIndex(Savegame sg, long gameTime) {
        return anchor(sg).monthIndex(gameTime);
    }

    public long monthStart(Savegame sg, long monthIndex) {
        return anchor(sg).monthStart(monthIndex);
    }

    /** Start of the month {@code months} months after the month containing {@code gameTime} (negative = earlier). */
    public long addMonths(Savegame sg, long gameTime, long months) {
        Anchor a = anchor(sg);
        return a.monthStart(a.monthIndex(gameTime) + months);
    }

    /** FS25 period (1..12, 1 = March) of the month containing {@code gameTime}. */
    public int periodOfYear(Savegame sg, long gameTime) {
        Anchor a = anchor(sg);
        return a.periodOf(a.monthIndex(gameTime));
    }

    /** Whether {@code gameTime} is exactly the start of a month. */
    public boolean isMonthStart(Savegame sg, long gameTime) {
        Anchor a = anchor(sg);
        return a.monthStart(a.monthIndex(gameTime)) == gameTime;
    }
}
