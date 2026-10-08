package de.farmpulse.rpsim.narration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.farmpulse.rpsim.common.FixedDelayThread;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Separate worker that turns pending narration jobs into communications (decoupled from the fact layer).
 * <p>
 * Technical review 10/2026, Phase 1.5 (R-3): runs on threads of its own - a slow AI provider no longer holds the
 * bridge cycle. {@code rpsim.ai.worker-threads} jobs are processed at the same time (default 1 = in job order).
 */
@Component
@ConditionalOnProperty(prefix = "rpsim.ai", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class NarrationWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NarrationWorker.class);
    /** On shutdown a running AI call gets this long; then it is interrupted and its lease lets it run again later. */
    static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private final NarrationJobRepository jobs;
    private final AiNarrationService narration;
    private final int threads;
    private final FixedDelayThread poller;
    private ExecutorService pool;

    public NarrationWorker(NarrationJobRepository jobs, AiNarrationService narration, RpsimProperties props) {
        this.jobs = jobs;
        this.narration = narration;
        this.threads = Math.max(1, props.getAi().getWorkerThreads());
        this.poller = new FixedDelayThread("narration-poll", Duration.ofMillis(props.getAi().getWorkerIntervalMs()), this::run);
    }

    void run() {
        List<Long> ids = jobs.findClaimableIds(Instant.now());
        if (ids.isEmpty()) {
            return;
        }
        List<Callable<Void>> work = ids.stream().<Callable<Void>>map(id -> () -> {
            process(id);
            return null;
        }).toList();
        try {
            pool.invokeAll(work); // one run at a time: the next poll starts after this batch
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(Long jobId) {
        try {
            narration.process(jobId);
        } catch (RuntimeException e) {
            log.warn("Narration job {} failed: {}", jobId, e.getMessage());
        }
    }

    @Override
    public synchronized void start() {
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "narration-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        poller.start();
    }

    @Override
    public synchronized void stop() {
        poller.stop(STOP_GRACE);
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        try {
            pool.awaitTermination(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool = null;
    }

    @Override
    public boolean isRunning() {
        return poller.isRunning();
    }
}
