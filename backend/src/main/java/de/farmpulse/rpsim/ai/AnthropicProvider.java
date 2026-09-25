package de.farmpulse.rpsim.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.springframework.stereotype.Component;

/**
 * Anthropic Messages API via the official Java SDK. JSON output is enforced through the prompt and parsed by
 * {@link AiOutputParser}. Server-side refusal fallbacks are enabled ({@code fallbacks: "default"}); a remaining
 * refusal is treated like any other failure and ends in the fallback template.
 */
@Component
public class AnthropicProvider implements AiProvider {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final long MAX_TOKENS = 16000;

    private final AiSettingsService settings;
    private AnthropicClient client;
    private String clientKey;

    public AnthropicProvider(AiSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "ANTHROPIC";
    }

    private synchronized AnthropicClient client(AiSettingsService.ProviderSettings s) {
        String key = s.apiKey() + "|" + s.baseUrl();
        if (client == null || !Objects.equals(key, clientKey)) {
            client = AnthropicOkHttpClient.builder()
                    .apiKey(s.apiKey())
                    .baseUrl(s.baseUrl())
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .maxRetries(1)
                    .build();
            clientKey = key;
        }
        return client;
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        AiSettingsService.ProviderSettings s = settings.settings(id());
        if (s.apiKey() == null || s.apiKey().isBlank()) {
            throw new AiProviderException("Kein Anthropic-API-Key hinterlegt");
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(s.model())
                .maxTokens(MAX_TOKENS)
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .addBeta(FALLBACK_BETA)
                .fallbacks(BetaFallbacksParam.ofDefault())
                .build();
        BetaMessage message;
        try {
            message = client(s).beta().messages().create(params);
        } catch (AnthropicException e) {
            throw new AiProviderException("Anthropic: " + e.getMessage(), e);
        }
        if (message.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
            throw new AiProviderException("Anthropic refusal");
        }
        String text = message.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        return AiOutputParser.parse(text);
    }
}
