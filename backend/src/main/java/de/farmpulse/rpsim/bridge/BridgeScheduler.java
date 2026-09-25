package de.farmpulse.rpsim.bridge;

import java.nio.file.Files;
import java.nio.file.Path;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Real-time polling of the bridge folder (the only real-time timer; all game logic runs on game time). */
@Component
@ConditionalOnProperty(prefix = "rpsim.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BridgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(BridgeScheduler.class);

    private final BridgeSyncService sync;
    private final RpsimProperties props;

    public BridgeScheduler(BridgeSyncService sync, RpsimProperties props) {
        this.sync = sync;
        this.props = props;
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

    @Scheduled(fixedDelayString = "${rpsim.bridge.poll-interval-ms:2000}")
    public void poll() {
        try {
            sync.runCycle();
        } catch (RuntimeException e) {
            log.warn("Bridge cycle failed (retry next cycle): {}", e.getMessage(), e);
        }
    }
}
