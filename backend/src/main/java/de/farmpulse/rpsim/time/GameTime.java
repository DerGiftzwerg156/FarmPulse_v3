package de.farmpulse.rpsim.time;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.springframework.stereotype.Component;

/**
 * Game-time arithmetic. gameTime = in-game milliseconds since savegame start (see bridge protocol).
 * Months/years use the configurable fallback counter (TODO(offene-frage): FS25 period field).
 */
@Component
public class GameTime {

    public static final long MS_PER_HOUR = 60L * 60 * 1000;
    public static final long MS_PER_DAY = 24 * MS_PER_HOUR;

    private final RpsimProperties props;

    public GameTime(RpsimProperties props) {
        this.props = props;
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

    public long msPerMonth() {
        return props.getTime().getGameDaysPerMonth() * MS_PER_DAY;
    }

    public long monthIndex(long gameTime) {
        return Math.floorDiv(gameTime, msPerMonth());
    }

    public long daysPerYear() {
        return (long) props.getTime().getGameDaysPerMonth() * props.getTime().getMonthsPerYear();
    }

    public long yearIndex(long gameTime) {
        return Math.floorDiv(dayIndex(gameTime), daysPerYear());
    }

    /** Day within the (fallback) game year, 0-based. */
    public long dayOfYear(long gameTime) {
        return Math.floorMod(dayIndex(gameTime), daysPerYear());
    }

    public static double toDays(long ms) {
        return ms / (double) MS_PER_DAY;
    }
}
