package de.farmpulse.rpsim.ai;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Local Ollama instance ({@code /api/chat}, JSON format, no API key). Default http://localhost:11434. */
@Component
public class OllamaProvider implements AiProvider {

    private final AiHttp http;
    private final AiSettingsService settings;
    private final JsonMapper json;

    public OllamaProvider(AiHttp http, AiSettingsService settings, JsonMapper json) {
        this.http = http;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public String id() {
        return "OLLAMA";
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        AiSettingsService.ProviderSettings s = settings.settings(id());
        Map<String, Object> body = Map.of(
                "model", s.model(),
                "stream", false,
                "format", "json",
                "messages", List.of(Map.of("role", "system", "content", prompt.system()),
                        Map.of("role", "user", "content", prompt.user())));
        AiHttp.Response r;
        try {
            r = http.postJson(URI.create(OpenAiProvider.trim(s.baseUrl()) + "/api/chat"), Map.of(),
                    json.writeValueAsString(body), Duration.ofSeconds(settings.timeoutSeconds()));
        } catch (AiProviderException e) {
            throw new AiProviderException("Ollama ist unter " + s.baseUrl() + " nicht erreichbar (läuft `ollama serve`?): "
                    + e.getMessage(), e);
        }
        if (r.status() == 404) {
            throw new AiProviderException("Ollama-Modell '" + s.model() + "' nicht gefunden (ollama pull " + s.model() + ")");
        }
        if (r.status() / 100 != 2) {
            throw new AiProviderException("Ollama HTTP " + r.status());
        }
        return AiOutputParser.parse(json.readTree(r.body()).path("message").path("content").asString(null));
    }
}
