package de.farmpulse.rpsim.ai;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Gemini (Generative Language API, generateContent) with JSON output
 * ({@code responseMimeType: application/json}). The free-tier model name changes frequently, so it is purely
 * configuration - see docs/dev/ai-providers.md for how the default was chosen and how to update it.
 */
@Component
public class GeminiProvider implements AiProvider {

    private final AiHttp http;
    private final AiSettingsService settings;
    private final JsonMapper json;

    public GeminiProvider(AiHttp http, AiSettingsService settings, JsonMapper json) {
        this.http = http;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public String id() {
        return "GEMINI";
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        AiSettingsService.ProviderSettings s = settings.settings(id());
        if (s.apiKey() == null || s.apiKey().isBlank()) {
            throw new AiProviderException("Kein Gemini-API-Key hinterlegt");
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", prompt.system()))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt.user())))),
                "generationConfig", Map.of("responseMimeType", "application/json"));
        URI uri = URI.create(OpenAiProvider.trim(s.baseUrl()) + "/models/"
                + URLEncoder.encode(s.model(), StandardCharsets.UTF_8) + ":generateContent");
        AiHttp.Response r = http.postJson(uri, Map.of("x-goog-api-key", s.apiKey()), json.writeValueAsString(body),
                Duration.ofSeconds(settings.timeoutSeconds()));
        if (r.status() / 100 != 2) {
            throw new AiProviderException("Gemini HTTP " + r.status());
        }
        JsonNode candidate = json.readTree(r.body()).path("candidates").path(0);
        if ("SAFETY".equals(candidate.path("finishReason").asString(""))) {
            throw new AiProviderException("Gemini safety block");
        }
        return AiOutputParser.parse(candidate.path("content").path("parts").path(0).path("text").asString(null));
    }
}
