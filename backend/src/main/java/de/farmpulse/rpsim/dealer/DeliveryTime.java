package de.farmpulse.rpsim.dealer;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.employee.AbsenceScale;
import de.farmpulse.rpsim.time.GameTime;

/**
 * Roadmap vehicle dealer, "Zeitrechnung" (owner decision 2026-10-15): delivery times are days of a month with
 * absence-scaling.reference-days-per-period days, scaled to the days per period of the savegame and counted to the hour
 * (game hours = days x daysPerPeriod / reference x 24). Never before the earliest hour (9:00) of the next game day after
 * the order; a time before that hour of its day moves to that hour of the same day.
 */
public final class DeliveryTime {

    /**
     * Expected delivery: game times of the earliest and latest moment, with and without express (L3);
     * {@code earliestAt} = the earliest hour of the next game day (a bound equal to it reads "frühestens morgen 9 Uhr").
     */
    public record Estimate(long fromGameTime, long earliestAt, long minAt, long maxAt, long expressMinAt,
                           long expressMaxAt) {
    }

    private DeliveryTime() {
    }

    /** Game time of a delivery {@code days} reference days after {@code from}. */
    public static long at(long from, double days, int daysPerPeriod, RpsimProperties.AbsenceScaling scaling,
                          int earliestHour) {
        double factor = AbsenceScale.factor(daysPerPeriod, scaling);
        long at = from + Math.round(days * factor * GameTime.MS_PER_DAY);
        long earliest = (GameTime.dayIndex(from) + 1) * GameTime.MS_PER_DAY + earliestHour * GameTime.MS_PER_HOUR;
        at = Math.max(at, earliest);
        long dayStart = GameTime.dayIndex(at) * GameTime.MS_PER_DAY;
        return Math.max(at, dayStart + earliestHour * GameTime.MS_PER_HOUR);
    }

    /** The delivery span from {@code from} for a span of reference days, with and without express. */
    public static Estimate estimate(long from, double daysMin, double daysMax, int daysPerPeriod,
                                    RpsimProperties.AbsenceScaling scaling, RpsimProperties.MachineDealer cfg) {
        int hour = cfg.getEarliestDeliveryHour();
        double express = cfg.getExpressFactor();
        long earliest = (GameTime.dayIndex(from) + 1) * GameTime.MS_PER_DAY + hour * GameTime.MS_PER_HOUR;
        return new Estimate(from, earliest, at(from, daysMin, daysPerPeriod, scaling, hour),
                at(from, daysMax, daysPerPeriod, scaling, hour), at(from, daysMin * express, daysPerPeriod, scaling, hour),
                at(from, daysMax * express, daysPerPeriod, scaling, hour));
    }
}
