package de.farmpulse.rpsim.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiProviderRegistryTest {

    @Autowired AiProviderRegistry registry;
    @Autowired AiSettingsService settings;
    @Autowired FakeAiProvider fake;
    @Autowired RpsimProperties props;

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(Path.of(props.getAi().getLocalConfigFile()));
        fake.reset();
    }

    @Test
    void defaultIsNoneWhichAlwaysFails() {
        assertThat(registry.active().id()).isEqualTo("NONE");
        assertThatThrownBy(() -> registry.active().generate(new AiPrompt("s", "u"))).isInstanceOf(AiProviderException.class);
    }

    @Test
    void localSettingsSelectProviderAndKeyIsNeverExposed() {
        AiSettingsService.View v = settings.save("fake", null, "secret-key", null);
        assertThat(v.provider()).isEqualTo("FAKE");
        assertThat(v.apiKeySet()).isTrue();
        assertThat(v.toString()).doesNotContain("secret-key");
        assertThat(registry.active()).isSameAs(fake);
        assertThat(registry.active().generate(new AiPrompt("s", "u")).body()).isNotBlank();
        assertThat(fake.prompts()).hasSize(1);
    }

    @Test
    void modelAndKeyStoredPerProvider() {
        settings.save("ANTHROPIC", "claude-sonnet-5", "sk-ant-x", null);
        settings.save("OPENAI", null, null, null);
        assertThat(settings.settings("ANTHROPIC").model()).isEqualTo("claude-sonnet-5");
        assertThat(settings.settings("ANTHROPIC").apiKey()).isEqualTo("sk-ant-x");
        assertThat(settings.settings("OPENAI").model()).isEqualTo(props.getAi().getOpenai().getModel());
        assertThat(registry.ids()).contains("OPENAI", "ANTHROPIC", "GEMINI", "OLLAMA", "FAKE", "NONE");
    }

    @Test
    void outputParserToleratesFencesAndRejectsGarbage() {
        AiResult r = AiOutputParser.parse("```json\n{\"subject\": \"Hallo\", \"body\": \"Text\"}\n```");
        assertThat(r).isEqualTo(new AiResult("Hallo", "Text"));
        assertThatThrownBy(() -> AiOutputParser.parse("kein json")).isInstanceOf(AiProviderException.class);
        assertThatThrownBy(() -> AiOutputParser.parse("{\"subject\": \"x\"}")).isInstanceOf(AiProviderException.class);
    }
}
