package de.farmpulse.rpsim.ai;

import static de.farmpulse.rpsim.ai.ProviderTestSupport.JSON;
import static de.farmpulse.rpsim.ai.ProviderTestSupport.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the SDK against a local mock HTTP server - no real API call. */
class AnthropicProviderTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final List<String> betaHeaders = new ArrayList<>();
    volatile int status = 200;
    volatile String response;

    static String message(String stopReason, String text) {
        return """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"text","text":%s}],"stop_reason":"%s","stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":20}}""".formatted(JSON.writeValueAsString(text), stopReason);
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            betaHeaders.add(ex.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    AnthropicProvider provider(String key) {
        AiSettingsService s = ProviderTestSupport.settings("ANTHROPIC", key);
        s.save("ANTHROPIC", null, null, "http://127.0.0.1:" + server.getAddress().getPort());
        return new AnthropicProvider(s);
    }

    @Test
    void sendsSystemPromptModelAndFallbacks() {
        response = message("end_turn", "{\"subject\":\"Kredit\",\"body\":\"Guten Tag!\"}");
        AiResult r = provider("sk-ant-test").generate(PROMPT);
        assertThat(r).isEqualTo(new AiResult("Kredit", "Guten Tag!"));
        var body = JSON.readTree(bodies.get(0));
        assertThat(body.path("model").asString()).isEqualTo("claude-opus-5");
        assertThat(body.path("system").asString()).isEqualTo("SYSTEM-TEXT");
        assertThat(body.path("messages").path(0).path("content").asString()).isEqualTo("USER-TEXT");
        assertThat(body.path("fallbacks").asString()).isEqualTo("default");
        assertThat(betaHeaders.get(0)).contains(AnthropicProvider.FALLBACK_BETA);
    }

    @Test
    void refusalAndHttpErrorsBecomeProviderExceptions() {
        response = message("refusal", "");
        assertThatThrownBy(() -> provider("k").generate(PROMPT)).isInstanceOf(AiProviderException.class)
                .hasMessageContaining("refusal");
        status = 400;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}";
        assertThatThrownBy(() -> provider("k").generate(PROMPT)).isInstanceOf(AiProviderException.class);
    }

    @Test
    void missingKeyFailsWithoutCall() {
        assertThatThrownBy(() -> provider(null).generate(PROMPT)).hasMessageContaining("Key");
        assertThat(bodies).isEmpty();
    }
}
