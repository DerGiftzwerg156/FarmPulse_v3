package de.farmpulse.rpsim.ai;

import static de.farmpulse.rpsim.ai.ProviderTestSupport.JSON;
import static de.farmpulse.rpsim.ai.ProviderTestSupport.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OllamaProviderTest {

    MockAiHttp http = new MockAiHttp();

    @Test
    void chatRequestWithoutKey() {
        OllamaProvider p = new OllamaProvider(http, ProviderTestSupport.settings("OLLAMA", null), JSON);
        http.next = () -> new AiHttp.Response(200,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"subject\\\":\\\"S\\\",\\\"body\\\":\\\"B\\\"}\"}}");
        assertThat(p.generate(PROMPT).body()).isEqualTo("B");
        MockAiHttp.Request req = http.requests.get(0);
        assertThat(req.uri().toString()).isEqualTo("http://localhost:11434/api/chat");
        var body = JSON.readTree(req.body());
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("format").asString()).isEqualTo("json");
        assertThat(body.path("model").asString()).isEqualTo("llama3.1");
    }

    @Test
    void unreachableOllamaGivesClearMessage() {
        OllamaProvider p = new OllamaProvider(http, ProviderTestSupport.settings("OLLAMA", null), JSON);
        http.next = () -> {
            throw new AiProviderException("Keine Verbindung zu localhost:11434");
        };
        assertThatThrownBy(() -> p.generate(PROMPT)).isInstanceOf(AiProviderException.class)
                .hasMessageContaining("nicht erreichbar");
        http.next = () -> new AiHttp.Response(404, "{}");
        assertThatThrownBy(() -> p.generate(PROMPT)).hasMessageContaining("ollama pull");
    }
}
