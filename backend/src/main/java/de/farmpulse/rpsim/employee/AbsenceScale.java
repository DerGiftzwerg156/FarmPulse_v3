package de.farmpulse.rpsim.employee;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Component;

/**
 * Owner request 2026-10-14: absences of employees (sickness, work accident, days off, training) are given in days of a
 * month with reference-days-per-period days and are scaled to the FS25 "days per period" of the savegame - a 2-day
 * month must not lose an employee for a week. Rounded, at least one game day (the roster changes once per game day).
 * Without rpsim.formulas.absence-scaling.enabled the days are game days.
 */
@Component
public class AbsenceScale {

    private final RpsimProperties props;
    private final GameTime gameTime;

    public AbsenceScale(RpsimProperties props, GameTime gameTime) {
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.AbsenceScaling cfg() {
        return props.getFormulas().getAbsenceScaling();
    }

    /** Game days of an absence of {@code days} reference days at the given month length. */
    public static int gameDays(double days, int daysPerPeriod, RpsimProperties.AbsenceScaling cfg) {
        if (!cfg.isEnabled()) {
            return Math.max(1, (int) Math.round(days));
        }
        return Math.max(1, (int) Math.round(days * factor(daysPerPeriod, cfg)));
    }

    /** Game days per reference day (1 without scaling). */
    public static double factor(int daysPerPeriod, RpsimProperties.AbsenceScaling cfg) {
        return cfg.isEnabled() ? daysPerPeriod / (double) Math.max(1, cfg.getReferenceDaysPerPeriod()) : 1;
    }

    /** Game days of an absence of {@code days} reference days in the savegame. */
    public int gameDays(Savegame sg, double days) {
        return gameDays(days, daysPerPeriod(sg), cfg());
    }

    /** Reference days that {@code gameDays} game days stand for in the savegame (e.g. for the relief of days off). */
    public double referenceDays(Savegame sg, int gameDays) {
        return gameDays / factor(daysPerPeriod(sg), cfg());
    }

    public int daysPerPeriod(Savegame sg) {
        return gameTime.anchor(sg).daysPerPeriod();
    }

    public boolean enabled() {
        return cfg().isEnabled();
    }

    public int referenceDaysPerPeriod() {
        return cfg().getReferenceDaysPerPeriod();
    }

    /**
     * A running absence after a change of the days per period: the rest keeps its share of the month, at least one
     * game day, at most {@code maxGameDays} (null = no cap).
     */
    public long rescaleUntil(long until, long now, int oldDaysPerPeriod, int newDaysPerPeriod, Integer maxGameDays) {
        long scaled = Math.round((until - now) * (double) newDaysPerPeriod / oldDaysPerPeriod);
        if (maxGameDays != null) {
            scaled = Math.min(scaled, GameTime.days(maxGameDays));
        }
        return now + Math.max(GameTime.days(1), scaled);
    }
}
