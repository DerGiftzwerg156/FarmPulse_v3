package de.farmpulse.rpsim.ai;

import static de.farmpulse.rpsim.ai.ProviderTestSupport.JSON;
import static de.farmpulse.rpsim.ai.ProviderTestSupport.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenAiProviderTest {

    MockAiHttp http = new MockAiHttp();

    @Test
    void sendsJsonModeRequestAndParsesAnswer() {
        OpenAiProvider p = new OpenAiProvider(http, ProviderTestSupport.settings("OPENAI", "sk-test"), JSON);
        http.next = () -> new AiHttp.Response(200, """
            {"choices":[{"finish_reason":"stop","message":{"content":"{\\"subject\\":\\"Hallo\\",\\"body\\":\\"Moin!\\"}"}}]}""");
        assertThat(p.generate(PROMPT)).isEqualTo(new AiResult("Hallo", "Moin!"));
        MockAiHttp.Request req = http.requests.get(0);
        assertThat(req.uri().toString()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(req.headers()).containsEntry("authorization", "Bearer sk-test");
        var body = JSON.readTree(req.body());
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(body.path("model").asString()).isEqualTo("gpt-4o-mini");
        assertThat(body.path("messages").path(0).path("content").asString()).isEqualTo("SYSTEM-TEXT");
    }

    @Test
    void httpErrorAndMissingKeyFail() {
        OpenAiProvider p = new OpenAiProvider(http, ProviderTestSupport.settings("OPENAI", "k"), JSON);
        http.next = () -> new AiHttp.Response(429, "{}");
        assertThatThrownBy(() -> p.generate(PROMPT)).isInstanceOf(AiProviderException.class).hasMessageContaining("429");
        OpenAiProvider noKey = new OpenAiProvider(http, ProviderTestSupport.settings("OPENAI", null), JSON);
        assertThatThrownBy(() -> noKey.generate(PROMPT)).hasMessageContaining("Key");
    }

    @Test
    void timeoutIsPropagatedAsProviderException() {
        OpenAiProvider p = new OpenAiProvider(http, ProviderTestSupport.settings("OPENAI", "k"), JSON);
        http.next = () -> {
            throw new AiProviderException("Zeitüberschreitung");
        };
        assertThatThrownBy(() -> p.generate(PROMPT)).isInstanceOf(AiProviderException.class);
    }
}
