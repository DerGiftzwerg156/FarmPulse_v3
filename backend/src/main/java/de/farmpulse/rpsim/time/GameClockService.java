package de.farmpulse.rpsim.time;

import de.farmpulse.rpsim.cycle.CycleDispatcher;
import de.farmpulse.rpsim.cycle.CycleDispatcher.Outcome;
import de.farmpulse.rpsim.cycle.CycleQueue;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns game-time jumps of incoming snapshots into events: one {@link GameTimeAdvancedEvent} per snapshot plus
 * one {@link GameDayPassedEvent}/{@link GameMonthPassedEvent} per crossed day/month (month = FS25 period, TODO T-08).
 * Time is purely game time: while FS25 is paused nothing happens.
 * <p>
 * Technical review 10/2026, Phase 1.3 (R-1, R-4): the work of a snapshot ({@link TickWork}) is a queued cycle event.
 * Every listener runs in its own transaction ({@link CycleDispatcher}); the game time of a day is committed before its
 * listeners run, the finished day ({@code lastProcessedGameDay}) after them. A failing listener stops the work at
 * exactly this point - the next cycle continues there, so a long catch-up is resumable and never runs a day twice.
 */
@Service
public class GameClockService {

    private static final Logger log = LoggerFactory.getLogger(GameClockService.class);
    /** Safety cap for catch-up after long jumps (e.g. sleeping in game for weeks). */
    static final int MAX_CATCH_UP_DAYS = 400;

    /**
     * Game-time work of one farm_facts snapshot: from {@code previous} to {@code target}; {@code last} is the game time
     * of the last {@link GameTimeAdvancedEvent} already sent (null before the first day).
     */
    public record TickWork(long previous, long target, Long last) {
    }

    private final GameTime gameTime;
    private final SavegameRepository savegames;
    private final CycleDispatcher dispatcher;
    private final CycleQueue queue;
    private final TransactionTemplate tx;

    public GameClockService(GameTime gameTime, SavegameRepository savegames, CycleDispatcher dispatcher, CycleQueue queue,
                            PlatformTransactionManager transactions) {
        this.gameTime = gameTime;
        this.savegames = savegames;
        this.dispatcher = dispatcher;
        this.queue = queue;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Processes the queued game-time work {@code cycleEventId}; {@link Outcome#BLOCKED} = continue next cycle. */
    public Outcome advance(Long savegameId, Long cycleEventId, TickWork work) {
        long previous = work.previous();
        long now = work.target();
        Savegame sg = savegames.findById(savegameId).orElseThrow();
        if (sg.getLastProcessedGameDay() == null || now < previous) {
            // first snapshot or time went backwards (older save loaded): re-anchor without replaying days
            if (sg.getLastProcessedGameDay() != null) {
                log.info("Game time went backwards for savegame {} ({} -> {}), re-anchoring", savegameId, previous, now);
            }
            setTime(savegameId, now, GameTime.dayIndex(now));
            return dispatcher.dispatch(savegameId, cycleEventId, "time:final", new GameTimeAdvancedEvent(savegameId, now, now));
        }
        long day = GameTime.dayIndex(now);
        long from = sg.getLastProcessedGameDay() + 1;
        if (day - from > MAX_CATCH_UP_DAYS) {
            from = day - MAX_CATCH_UP_DAYS;
        }
        // Catch up day by day: every crossed day is processed at its own game time, so daily checks
        // (escalations, spawns, payroll) behave the same whether the player plays or skips time.
        long last = work.last() == null ? previous : work.last();
        for (long d = from; d <= day; d++) {
            long dayStart = d * GameTime.MS_PER_DAY;
            setTime(savegameId, dayStart, null);
            if (dispatcher.dispatch(savegameId, cycleEventId, "time:" + d,
                    new GameTimeAdvancedEvent(savegameId, last, dayStart)) == Outcome.BLOCKED) {
                return Outcome.BLOCKED;
            }
            if (dispatcher.dispatch(savegameId, cycleEventId, "day:" + d,
                    new GameDayPassedEvent(savegameId, d, dayStart)) == Outcome.BLOCKED) {
                return Outcome.BLOCKED;
            }
            Savegame current = savegames.findById(savegameId).orElseThrow();
            long prevMonth = gameTime.monthIndex(current, dayStart - 1);
            long month = gameTime.monthIndex(current, dayStart);
            if (month != prevMonth && dispatcher.dispatch(savegameId, cycleEventId, "month:" + d,
                    new GameMonthPassedEvent(savegameId, month, dayStart)) == Outcome.BLOCKED) {
                return Outcome.BLOCKED;
            }
            long processedDay = d;
            long lastSent = dayStart;
            tx.executeWithoutResult(s -> {
                savegames.findById(savegameId).orElseThrow().setLastProcessedGameDay(processedDay);
                queue.update(cycleEventId, new TickWork(previous, now, lastSent));
            });
            last = dayStart;
        }
        setTime(savegameId, now, null);
        return dispatcher.dispatch(savegameId, cycleEventId, "time:final", new GameTimeAdvancedEvent(savegameId, last, now));
    }

    private void setTime(Long savegameId, long time, Long processedDay) {
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(savegameId).orElseThrow();
            sg.setCurrentGameTime(time);
            if (processedDay != null) {
                sg.setLastProcessedGameDay(processedDay);
            }
        });
    }
}
