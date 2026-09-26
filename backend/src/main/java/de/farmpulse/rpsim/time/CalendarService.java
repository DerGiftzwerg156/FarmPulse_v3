package de.farmpulse.rpsim.time;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.domain.Savegame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * TODO T-08: keeps the FS25 calendar of every savegame (period, day in period, days per period, year) and the
 * monotonic month counter derived from it. Called for every ingested farm_facts.json before game time advances.
 */
@Service
public class CalendarService {

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

    private final GameTime gameTime;
    private final ApplicationEventPublisher events;

    public CalendarService(GameTime gameTime, ApplicationEventPublisher events) {
        this.gameTime = gameTime;
        this.events = events;
    }

    /**
     * Applies the exported calendar. The start of the current period is
     * {@code (monotonicDay - (dayInPeriod - 1)) * 1 game day} - the same day base as the mod's gameTime
     * ({@code currentMonotonicDay * 86 400 000 + dayTime}).
     */
    public void update(Savegame sg, BridgeDtos.Calendar c) {
        if (c == null || c.period() == null || c.daysPerPeriod() == null || c.monotonicDay() == null) {
            return;
        }
        int n = Math.max(1, c.daysPerPeriod());
        int dayInPeriod = c.dayInPeriod() == null ? 1 : Math.max(1, c.dayInPeriod());
        long monthStart = (c.monotonicDay() - (dayInPeriod - 1)) * GameTime.MS_PER_DAY;
        GameTime.Anchor old = gameTime.anchor(sg);
        boolean first = sg.getCalMonthIndex() == null;
        long index;
        if (first) {
            // continue the month count of the fallback calendar so dates scheduled before keep their month
            index = old.monthIndex(monthStart);
        } else if (monthStart == old.monthStart()) {
            index = old.monthIndex();
        } else if (n == old.daysPerPeriod() && Math.floorMod(monthStart - old.monthStart(), old.msPerMonth()) == 0) {
            index = old.monthIndex() + Math.floorDiv(monthStart - old.monthStart(), old.msPerMonth());
        } else {
            // the length changed in between: count the periods the game itself reports
            int diff = Math.floorMod(c.period() - old.period(), GameTime.PERIODS_PER_YEAR);
            if (monthStart < old.monthStart() && diff != 0) {
                diff -= GameTime.PERIODS_PER_YEAR;
            }
            index = old.monthIndex() + diff;
        }
        GameTime.Anchor current = new GameTime.Anchor(index, monthStart, n, c.period());
        sg.setCalMonthIndex(index);
        sg.setCalMonthStartGameTime(monthStart);
        sg.setCalDaysPerPeriod(n);
        sg.setCalPeriod(c.period());
        sg.setCalDayInPeriod(dayInPeriod);
        sg.setCalYear(c.year());
        sg.setCalPeriodName(c.periodName());
        sg.setCalSeason(c.season() == null ? null : c.season().substring(0, Math.min(64, c.season().length())));
        if (first || n != old.daysPerPeriod()) {
            if (!first) {
                log.info("Savegame {}: days per period changed {} -> {}, rescheduling monthly dates", sg.getId(),
                        old.daysPerPeriod(), n);
            }
            events.publishEvent(new CalendarChangedEvent(sg.getId(), old, current));
        }
    }
}
