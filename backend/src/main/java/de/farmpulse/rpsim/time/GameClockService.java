package de.farmpulse.rpsim.time;

import de.farmpulse.rpsim.domain.Savegame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Turns game-time jumps of incoming snapshots into events: one {@link GameTimeAdvancedEvent} per snapshot plus
 * one {@link GameDayPassedEvent}/{@link GameMonthPassedEvent} per crossed day/month. Time is purely game
 * time: while FS25 is paused nothing happens.
 */
@Service
public class GameClockService {

    private static final Logger log = LoggerFactory.getLogger(GameClockService.class);
    /** Safety cap for catch-up after long jumps (e.g. sleeping in game for weeks). */
    static final int MAX_CATCH_UP_DAYS = 400;

    private final ApplicationEventPublisher events;
    private final GameTime gameTime;

    public GameClockService(ApplicationEventPublisher events, GameTime gameTime) {
        this.events = events;
        this.gameTime = gameTime;
    }

    public void advance(Savegame sg, long previous, long now) {
        sg.setCurrentGameTime(now);
        long day = GameTime.dayIndex(now);
        if (sg.getLastProcessedGameDay() == null || now < previous) {
            // first snapshot or time went backwards (older save loaded): re-anchor without replaying days
            if (sg.getLastProcessedGameDay() != null) {
                log.info("Game time went backwards for savegame {} ({} -> {}), re-anchoring", sg.getId(), previous, now);
            }
            sg.setLastProcessedGameDay(day);
            events.publishEvent(new GameTimeAdvancedEvent(sg.getId(), now, now));
            return;
        }
        events.publishEvent(new GameTimeAdvancedEvent(sg.getId(), previous, now));
        long from = sg.getLastProcessedGameDay() + 1;
        if (day - from > MAX_CATCH_UP_DAYS) {
            from = day - MAX_CATCH_UP_DAYS;
        }
        for (long d = from; d <= day; d++) {
            long dayStart = d * GameTime.MS_PER_DAY;
            long prevMonth = gameTime.monthIndex(dayStart - 1);
            long month = gameTime.monthIndex(dayStart);
            events.publishEvent(new GameDayPassedEvent(sg.getId(), d, dayStart));
            if (month != prevMonth) {
                events.publishEvent(new GameMonthPassedEvent(sg.getId(), month, dayStart));
            }
            sg.setLastProcessedGameDay(d);
        }
    }
}
