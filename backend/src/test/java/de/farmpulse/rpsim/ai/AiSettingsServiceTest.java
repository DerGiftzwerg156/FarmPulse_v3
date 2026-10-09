package de.farmpulse.rpsim.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 10/2026 Phase 0.4/0.5 (S-2, S-3): the stored key only goes to the host it was entered for. */
class AiSettingsServiceTest {

    @TempDir
    Path tmp;

    RpsimProperties props;
    AiSettingsService settings;

    @BeforeEach
    void setUp() {
        props = new RpsimProperties();
        props.getAi().setLocalConfigFile(tmp.resolve("local-config/ai-provider.properties").toString());
        settings = new AiSettingsService(props);
    }

    @Test
    void aNewHostWithoutNewKeyDiscardsTheStoredKey() {
        settings.save("OPENAI", null, "sk-secret", null);
        assertThat(settings.view().apiKeySet()).isTrue();

        AiSettingsService.View v = settings.save("OPENAI", null, null, "https://evil.example/v1");

        assertThat(v.apiKeySet()).isFalse();
        assertThat(settings.settings("OPENAI").apiKey()).isEmpty();
        assertThat(settings.settings("OPENAI").baseUrl()).isEqualTo("https://evil.example/v1");
    }

    @Test
    void theSameHostKeepsTheKey() {
        settings.save("OPENAI", null, "sk-secret", null); // default https://api.openai.com/v1
        assertThat(settings.save("OPENAI", null, null, "https://API.openai.com/v2").apiKeySet()).isTrue();
        assertThat(settings.save("OPENAI", "gpt-x", null, null).apiKeySet()).isTrue();
        assertThat(settings.settings("OPENAI").apiKey()).isEqualTo("sk-secret");
    }

    @Test
    void aNewHostWithANewKeyKeepsTheNewKey() {
        settings.save("OPENAI", null, "sk-old", null);
        settings.save("OPENAI", null, "sk-proxy", "https://proxy.example/v1");
        assertThat(settings.settings("OPENAI").apiKey()).isEqualTo("sk-proxy");
    }

    @Test
    void aKeyFromTheYamlIsHiddenAsWell() {
        props.getAi().getAnthropic().setApiKey("sk-ant-from-yaml");
        assertThat(settings.settings("ANTHROPIC").apiKey()).isEqualTo("sk-ant-from-yaml");

        settings.save("ANTHROPIC", null, null, "https://evil.example");

        assertThat(settings.settings("ANTHROPIC").apiKey()).isEmpty();
        assertThat(settings.view().apiKeySet()).isFalse();
    }

    @Test
    void unparsableUrlsNeverCountAsTheSameHost() {
        assertThat(AiSettingsService.sameHost("https://api.openai.com/v1", "https://api.openai.com/x")).isTrue();
        assertThat(AiSettingsService.sameHost("http://localhost:11434", "http://localhost:9999")).isTrue();
        assertThat(AiSettingsService.sameHost("https://api.openai.com", "https://api.openai.com.evil.example")).isFalse();
        assertThat(AiSettingsService.sameHost("not a url", "not a url")).isFalse();
        assertThat(AiSettingsService.sameHost(null, "https://api.openai.com")).isFalse();
    }

    @Test
    void theFileIsReadableByTheOwnerOnly() throws IOException {
        settings.save("OPENAI", null, "sk-secret", null);
        Path file = Path.of(props.getAi().getLocalConfigFile());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void anOlderFileIsRestrictedOnStart() throws IOException {
        Path file = Path.of(props.getAi().getLocalConfigFile());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "provider=OPENAI\nopenai.apiKey=sk-old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        settings.restrictExistingFile();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(settings.settings("OPENAI").apiKey()).isEqualTo("sk-old");
    }
}
