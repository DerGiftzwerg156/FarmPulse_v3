package de.farmpulse.rpsim.narration;

import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate worker that turns pending narration jobs into communications (decoupled from the fact layer). */
@Component
@ConditionalOnProperty(prefix = "rpsim.ai", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class NarrationWorker {

    private static final Logger log = LoggerFactory.getLogger(NarrationWorker.class);

    private final NarrationJobRepository jobs;
    private final AiNarrationService narration;
    private final TransactionTemplate tx;

    public NarrationWorker(NarrationJobRepository jobs, AiNarrationService narration, TransactionTemplate tx) {
        this.jobs = jobs;
        this.narration = narration;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${rpsim.ai.worker-interval-ms:1500}")
    public void run() {
        for (NarrationJob j : jobs.findByStatusOrderByIdAsc(NarrationJobStatus.PENDING)) {
            try {
                tx.executeWithoutResult(s -> narration.process(jobs.findById(j.getId()).orElseThrow()));
            } catch (RuntimeException e) {
                log.warn("Narration job {} failed: {}", j.getId(), e.getMessage());
            }
        }
    }
}
