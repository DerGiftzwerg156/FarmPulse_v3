package de.farmpulse.rpsim.ai;

import static de.farmpulse.rpsim.ai.ProviderTestSupport.JSON;
import static de.farmpulse.rpsim.ai.ProviderTestSupport.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeminiProviderTest {

    MockAiHttp http = new MockAiHttp();

    @Test
    void generateContentRequestWithConfigurableModel() {
        AiSettingsService s = ProviderTestSupport.settings("GEMINI", "AIza-test");
        s.save("GEMINI", "gemini-free-model", null, null);
        GeminiProvider p = new GeminiProvider(http, s, JSON);
        http.next = () -> new AiHttp.Response(200, """
            {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\\"subject\\":\\"S\\",\\"body\\":\\"B\\"}"}]}}]}""");
        assertThat(p.generate(PROMPT)).isEqualTo(new AiResult("S", "B"));
        MockAiHttp.Request req = http.requests.get(0);
        assertThat(req.uri().toString())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-free-model:generateContent");
        assertThat(req.headers()).containsEntry("x-goog-api-key", "AIza-test");
        var body = JSON.readTree(req.body());
        assertThat(body.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("SYSTEM-TEXT");
    }

    @Test
    void safetyBlockAndHttpErrorFail() {
        GeminiProvider p = new GeminiProvider(http, ProviderTestSupport.settings("GEMINI", "k"), JSON);
        http.next = () -> new AiHttp.Response(200, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
        assertThatThrownBy(() -> p.generate(PROMPT)).isInstanceOf(AiProviderException.class);
        http.next = () -> new AiHttp.Response(503, "{}");
        assertThatThrownBy(() -> p.generate(PROMPT)).hasMessageContaining("503");
    }
}
