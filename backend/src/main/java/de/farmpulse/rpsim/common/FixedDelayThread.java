package de.farmpulse.rpsim.common;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Technical review 10/2026, Phase 1.5/1.7 (R-3, R-7): runs one task with a fixed delay on a thread of its own instead of
 * Spring's shared scheduler thread (one thread in Spring Boot unless configured otherwise), so a slow task cannot hold
 * the others. A run never overlaps the previous one. The thread is a daemon; {@link #stop} lets a running task finish
 * for up to the given time and interrupts it after that.
 * <p>
 * Deliberately no Spring bean: every {@code TaskScheduler}/{@code Executor} bean switches off Spring Boot's own
 * {@code taskScheduler} and {@code applicationTaskExecutor} ({@code @ConditionalOnMissingBean}).
 */
public final class FixedDelayThread {

    private static final Logger log = LoggerFactory.getLogger(FixedDelayThread.class);

    private final String name;
    private final Duration delay;
    private final Runnable task;
    private ScheduledExecutorService executor;

    public FixedDelayThread(String name, Duration delay, Runnable task) {
        this.name = name;
        this.delay = delay;
        this.task = task;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runGuarded, 0, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the thread: no new run starts, a running one gets {@code grace} to finish, then it is interrupted. */
    public synchronized void stop(Duration grace) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("{} still busy after {} s, interrupting it", name, grace.toSeconds());
                executor.shutdownNow();
                executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** An exception would cancel all further runs of a scheduled task - log it, the next run follows as planned. */
    private void runGuarded() {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("{} failed (next run follows): {}", name, e.getMessage(), e);
        }
    }
}
