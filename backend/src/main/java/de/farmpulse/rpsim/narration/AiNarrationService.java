package de.farmpulse.rpsim.narration;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.ai.AiPrompt;
import de.farmpulse.rpsim.ai.AiProvider;
import de.farmpulse.rpsim.ai.AiProviderException;
import de.farmpulse.rpsim.ai.AiProviderRegistry;
import de.farmpulse.rpsim.ai.AiResult;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.tone.ToneClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ONLY place in the backend that calls an {@link AiProvider}. It receives finished facts through a
 * {@link NarrationJob} (categories and decided values only) and returns text - it has no way to write a number
 * into the fact layer. Provider failures/timeouts fall back to a prepared template per event type, so the game
 * stays fully playable without a working AI connection.
 */
@Service
public class AiNarrationService {

    private static final Logger log = LoggerFactory.getLogger(AiNarrationService.class);

    private final AiProviderRegistry providers;
    private final PromptBuilder prompts;
    private final FallbackTemplates fallbacks;
    private final CommunicationService communications;
    private final ToneClassifier toneClassifier;
    private final MemoryService memory;
    private final RpsimProperties props;
    private final JsonMapper json;
    /** Roadmap V3.1 R31-D1 / R31-D2: receivers of texts that are no mail (newspaper article, chat message). */
    private final java.util.List<NarrationSink> sinks;
    private final NarrationJobRepository jobs;
    private final TransactionTemplate tx;

    public AiNarrationService(AiProviderRegistry providers, PromptBuilder prompts, FallbackTemplates fallbacks,
                              CommunicationService communications, ToneClassifier toneClassifier, MemoryService memory,
                              RpsimProperties props, JsonMapper json, java.util.List<NarrationSink> sinks,
                              NarrationJobRepository jobs, PlatformTransactionManager transactions) {
        this.sinks = sinks;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(transactions);
        this.providers = providers;
        this.prompts = prompts;
        this.fallbacks = fallbacks;
        this.communications = communications;
        this.toneClassifier = toneClassifier;
        this.memory = memory;
        this.props = props;
        this.json = json;
    }

    /** Facts of a claimed job, read in the claim transaction: everything the AI call needs, no entity. */
    record Claim(Long jobId, long version, NarrationEventType type, Map<String, Object> facts, AiPrompt prompt,
                 String characterName) {
    }

    /** Outcome of the AI call (or of the fallback template) - computed without a database transaction. */
    record Generated(AiResult result, boolean fallback, int attempts, String lastError) {
    }

    /** Processes one job if its game time has come. Returns the created communication or null. */
    public Communication process(NarrationJob job) {
        return process(job.getId());
    }

    /**
     * Technical review 10/2026, Phase 1.6 (R-3): three steps, so no database transaction is open while the AI
     * provider answers (up to {@code timeout-seconds} x {@code max-attempts}): claim the job (transaction 1,
     * {@code IN_PROGRESS} + lease), call the AI (no transaction), store the text (transaction 2). If the store step
     * fails the job goes back to {@code PENDING}; if the backend stops during the call the lease runs out and the
     * job is claimed again.
     */
    public Communication process(Long jobId) {
        Claim claim = tx.execute(s -> claim(jobId));
        if (claim == null) {
            return null;
        }
        Generated generated = null;
        try {
            generated = generate(claim);
            Generated result = generated;
            return tx.execute(s -> store(claim, result));
        } catch (RuntimeException e) {
            release(claim, generated, e);
            throw e;
        }
    }

    /** Lease of a claim: the longest the AI step may take (the Anthropic SDK retries once) plus a minute of slack. */
    Duration lease() {
        return Duration.ofSeconds(2L * Math.max(1, props.getAi().getTimeoutSeconds())
                * Math.max(1, props.getAi().getMaxAttempts()) + 60);
    }

    private Claim claim(Long jobId) {
        NarrationJob job = jobs.findById(jobId).orElse(null);
        if (job == null || !claimable(job)) {
            return null;
        }
        job.setStatus(NarrationJobStatus.IN_PROGRESS);
        job.setLeaseUntil(Instant.now().plus(lease()));
        jobs.flush(); // the version of this claim, checked again when the result is stored
        NarrationEventType type = NarrationEventType.valueOf(job.getEventType());
        Map<String, Object> facts = json.readValue(job.getFactsJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
        List<String> memoryFacts = job.getCharacter() == null ? List.of() : memory.shortFacts(job.getCharacter());
        boolean mechanical = job.getPlayerMessage() != null && toneClassifier.classify(job.getPlayerMessage()).mechanicalRequest();
        AiPrompt prompt = prompts.build(new PromptBuilder.Input(job.getCharacter(), job.getSavegame().getTonePreset(), type,
                job.getChannel(), facts, memoryFacts, job.getPlayerMessage(), mechanical,
                CalendarText.describe(job.getSavegame()), job.getTargetType()));
        return new Claim(job.getId(), job.getVersion(), type, facts, prompt,
                job.getCharacter() == null ? null : job.getCharacter().getName());
    }

    private static boolean claimable(NarrationJob job) {
        if (job.getStatus() == NarrationJobStatus.IN_PROGRESS) {
            return job.getLeaseUntil() == null || job.getLeaseUntil().isBefore(Instant.now());
        }
        return job.getStatus() == NarrationJobStatus.PENDING
                && job.getNotBeforeGameTime() <= job.getSavegame().getCurrentGameTime();
    }

    private Generated generate(Claim claim) {
        AiResult result = null;
        String lastError = null;
        int calls = 0;
        AiProvider provider = providers.active();
        int attempts = Math.max(1, props.getAi().getMaxAttempts());
        for (int i = 0; i < attempts && result == null; i++) {
            calls++;
            try {
                result = provider.generate(claim.prompt());
            } catch (AiProviderException | IllegalStateException e) {
                lastError = truncate(e.getMessage());
                if (!"NONE".equals(provider.id())) {
                    log.warn("AI provider {} failed for job {} ({}): {}", provider.id(), claim.jobId(), claim.type(), e.getMessage());
                } else {
                    break;
                }
            }
        }
        if (result != null) {
            return new Generated(result, false, calls, lastError);
        }
        return new Generated(fallbacks.render(claim.type(), claim.facts(), claim.characterName()), true, calls, lastError);
    }

    private Communication store(Claim claim, Generated generated) {
        NarrationJob job = jobs.findById(claim.jobId()).orElse(null);
        if (job == null || job.getStatus() != NarrationJobStatus.IN_PROGRESS || job.getVersion() != claim.version()) {
            log.info("Narration job {} was claimed again in the meantime, result dropped", claim.jobId());
            return null;
        }
        count(job, generated);
        AiResult result = generated.result();
        boolean fallback = generated.fallback();
        job.setLeaseUntil(null);
        job.setUsedFallback(fallback);
        if (job.getTargetType() != null) {
            sinks.stream().filter(s -> s.targetType().equals(job.getTargetType())).findFirst()
                    .ifPresent(s -> s.deliver(job, result.subject(), result.body(), fallback));
            job.setStatus(fallback ? NarrationJobStatus.FALLBACK : NarrationJobStatus.DONE);
            return null;
        }
        Communication c = communications.create(new CommunicationService.Draft(job.getSavegame(), job.getCharacter(),
                job.getChannel(), CommunicationInitiator.CHARACTER, result.subject(), result.body(), job.getCategory(),
                claim.type().name(), job.getRelatedEntityType(), job.getRelatedEntityId(), job.getThreadRootId(),
                job.getFormLink(), fallback, null));
        job.setCommunicationId(c.getId());
        job.setStatus(fallback ? NarrationJobStatus.FALLBACK : NarrationJobStatus.DONE);
        return c;
    }

    /**
     * The AI step failed unexpectedly ({@code generated} null) or the store step failed: the job is pending again (the
     * next worker run retries it), the AI calls made are counted.
     */
    private void release(Claim claim, Generated generated, RuntimeException cause) {
        try {
            tx.executeWithoutResult(s -> jobs.findById(claim.jobId())
                    .filter(j -> j.getStatus() == NarrationJobStatus.IN_PROGRESS && j.getVersion() == claim.version())
                    .ifPresent(j -> {
                        count(j, generated);
                        j.setLastError(truncate(cause.getMessage()));
                        j.setStatus(NarrationJobStatus.PENDING);
                        j.setLeaseUntil(null);
                    }));
        } catch (RuntimeException e) {
            log.warn("Narration job {} stays claimed until its lease runs out: {}", claim.jobId(), e.getMessage());
        }
    }

    private static void count(NarrationJob job, Generated generated) {
        if (generated == null) {
            return;
        }
        job.setAttempts(job.getAttempts() + generated.attempts());
        if (generated.lastError() != null) {
            job.setLastError(generated.lastError());
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
