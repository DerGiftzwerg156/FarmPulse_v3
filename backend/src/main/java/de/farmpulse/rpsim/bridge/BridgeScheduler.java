package de.farmpulse.rpsim.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Real-time polling of the bridge folder (the only real-time timer; all game logic runs on game time). */
@Component
@ConditionalOnProperty(prefix = "rpsim.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BridgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(BridgeScheduler.class);

    private final BridgeSyncService sync;

    public BridgeScheduler(BridgeSyncService sync) {
        this.sync = sync;
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
