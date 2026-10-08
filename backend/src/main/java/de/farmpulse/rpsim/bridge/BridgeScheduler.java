package de.farmpulse.rpsim.bridge;

import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;

import de.farmpulse.rpsim.common.FixedDelayThread;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.prompt.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Real-time polling of the bridge folder (the only real-time timer; all game logic runs on game time).
 * <p>
 * Technical review 10/2026, Phase 1.5 (R-3): the cycle runs on a thread of its own ({@code bridge}), strictly one
 * after the other - neither the AI worker nor the SSE keep-alive share it. On shutdown a running cycle may finish
 * ({@link #STOP_GRACE}); an interrupted one continues from its queue on the next start.
 */
@Component
@ConditionalOnProperty(prefix = "rpsim.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BridgeScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BridgeScheduler.class);
    static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private final BridgeSyncService sync;
    private final PromptService prompts;
    private final RpsimProperties props;
    private final FixedDelayThread thread;

    public BridgeScheduler(BridgeSyncService sync, PromptService prompts, RpsimProperties props) {
        this.sync = sync;
        this.prompts = prompts;
        this.props = props;
        this.thread = new FixedDelayThread("bridge", Duration.ofMillis(props.getBridge().getPollIntervalMs()), this::poll);
    }

    @Override
    public void start() {
        thread.start();
    }

    @Override
    public void stop() {
        thread.stop(STOP_GRACE);
    }

    @Override
    public boolean isRunning() {
        return thread.isRunning();
    }

    /** Tells the player where the backend looks for the mod files (first thing to check when nothing arrives). */
    @EventListener(ApplicationReadyEvent.class)
    public void logBridgePath() {
        Path dir = Path.of(props.getBridge().getPath()).toAbsolutePath().normalize();
        if (Files.isDirectory(dir.resolve("export"))) {
            log.info("Bridge folder: {}", dir);
        } else {
            log.warn("Bridge folder {} has no export/ yet - load a savegame with the FS25_RPSim mod or adjust rpsim.bridge.path", dir);
        }
    }

    public void poll() {
        try {
            sync.runCycle();
        } catch (RuntimeException e) {
            log.warn("Bridge cycle failed (retry next cycle): {}", e.getMessage(), e);
        }
        try {
            prompts.processAnswers(); // R2-F: answers from the game, each in its own transaction
        } catch (RuntimeException e) {
            log.warn("Answers from the game failed (retry next cycle): {}", e.getMessage(), e);
        }
    }
}
