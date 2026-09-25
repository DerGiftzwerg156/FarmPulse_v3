package de.farmpulse.rpsim.ai;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI Chat Completions API with JSON mode ({@code response_format: json_object}). Model configurable. */
@Component
public class OpenAiProvider implements AiProvider {

    private final AiHttp http;
    private final AiSettingsService settings;
    private final JsonMapper json;

    public OpenAiProvider(AiHttp http, AiSettingsService settings, JsonMapper json) {
        this.http = http;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public String id() {
        return "OPENAI";
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        AiSettingsService.ProviderSettings s = settings.settings(id());
        if (s.apiKey() == null || s.apiKey().isBlank()) {
            throw new AiProviderException("Kein OpenAI-API-Key hinterlegt");
        }
        Map<String, Object> body = Map.of(
                "model", s.model(),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", prompt.system()),
                        Map.of("role", "user", "content", prompt.user())));
        AiHttp.Response r = http.postJson(URI.create(trim(s.baseUrl()) + "/chat/completions"),
                Map.of("authorization", "Bearer " + s.apiKey()), json.writeValueAsString(body),
                Duration.ofSeconds(settings.timeoutSeconds()));
        if (r.status() / 100 != 2) {
            throw new AiProviderException("OpenAI HTTP " + r.status());
        }
        JsonNode choice = json.readTree(r.body()).path("choices").path(0);
        if ("content_filter".equals(choice.path("finish_reason").asString(""))) {
            throw new AiProviderException("OpenAI content filter");
        }
        return AiOutputParser.parse(choice.path("message").path("content").asString(null));
    }

    static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
