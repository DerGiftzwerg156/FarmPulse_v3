package de.farmpulse.rpsim.narration;

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
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.tone.ToneClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public AiNarrationService(AiProviderRegistry providers, PromptBuilder prompts, FallbackTemplates fallbacks,
                              CommunicationService communications, ToneClassifier toneClassifier, MemoryService memory,
                              RpsimProperties props, JsonMapper json) {
        this.providers = providers;
        this.prompts = prompts;
        this.fallbacks = fallbacks;
        this.communications = communications;
        this.toneClassifier = toneClassifier;
        this.memory = memory;
        this.props = props;
        this.json = json;
    }

    /** Processes one job if its game time has come. Returns the created communication or null. */
    @Transactional
    public Communication process(NarrationJob job) {
        if (job.getStatus() != NarrationJobStatus.PENDING
                || job.getNotBeforeGameTime() > job.getSavegame().getCurrentGameTime()) {
            return null;
        }
        NarrationEventType type = NarrationEventType.valueOf(job.getEventType());
        Map<String, Object> facts = json.readValue(job.getFactsJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
        List<String> memoryFacts = job.getCharacter() == null ? List.of() : memory.shortFacts(job.getCharacter());
        boolean mechanical = job.getPlayerMessage() != null && toneClassifier.classify(job.getPlayerMessage()).mechanicalRequest();
        AiPrompt prompt = prompts.build(new PromptBuilder.Input(job.getCharacter(), job.getSavegame().getTonePreset(), type,
                job.getChannel(), facts, memoryFacts, job.getPlayerMessage(), mechanical,
                CalendarText.describe(job.getSavegame())));
        AiResult result = null;
        boolean fallback = false;
        AiProvider provider = providers.active();
        int attempts = Math.max(1, props.getAi().getMaxAttempts());
        for (int i = 0; i < attempts && result == null; i++) {
            job.setAttempts(job.getAttempts() + 1);
            try {
                result = provider.generate(prompt);
            } catch (AiProviderException | IllegalStateException e) {
                job.setLastError(truncate(e.getMessage()));
                if (!"NONE".equals(provider.id())) {
                    log.warn("AI provider {} failed for job {} ({}): {}", provider.id(), job.getId(), type, e.getMessage());
                } else {
                    break;
                }
            }
        }
        if (result == null) {
            fallback = true;
            result = fallbacks.render(type, facts, job.getCharacter() == null ? null : job.getCharacter().getName());
        }
        Communication c = communications.create(new CommunicationService.Draft(job.getSavegame(), job.getCharacter(),
                job.getChannel(), CommunicationInitiator.CHARACTER, result.subject(), result.body(), job.getCategory(),
                type.name(), job.getRelatedEntityType(), job.getRelatedEntityId(), job.getThreadRootId(),
                job.getFormLink(), fallback, null));
        job.setCommunicationId(c.getId());
        job.setUsedFallback(fallback);
        job.setStatus(fallback ? NarrationJobStatus.FALLBACK : NarrationJobStatus.DONE);
        return c;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
