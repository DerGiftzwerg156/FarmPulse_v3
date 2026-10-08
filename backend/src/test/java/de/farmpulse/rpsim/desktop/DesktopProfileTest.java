package de.farmpulse.rpsim.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Profile "desktop": the settings of the setup (farmpulse-setup.yml, as the Inno Setup script writes it - ASCII with
 * \\u escapes) are imported from the data folder, the player's application-local.yml there wins.
 */
class DesktopProfileTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    @TempDir
    Path dataDir;

    @Test
    void setupFileThenOwnOverrides() throws IOException {
        Files.writeString(dataDir.resolve("farmpulse-setup.yml"), """
                # Geschrieben vom FarmPulse-Setup
                server:
                  port: 8123
                rpsim:
                  bridge:
                    path: "C:/Users/J\\u00fcrgen/OneDrive/Dokumente/My Games/FarmingSimulator2025/modSettings/FS25_RPSim"
                  desktop:
                    open-browser: false
                """);
        try (ConfigurableApplicationContext ctx = start()) {
            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("server.port")).isEqualTo("8123");
            assertThat(env.getProperty("rpsim.bridge.path"))
                    .isEqualTo("C:/Users/Jürgen/OneDrive/Dokumente/My Games/FarmingSimulator2025/modSettings/FS25_RPSim");
            assertThat(env.getProperty("rpsim.desktop.open-browser")).isEqualTo("false");
            assertThat(env.getProperty("rpsim.ai.local-config-file"))
                    .isEqualTo(dataDir.toString().replace('\\', '/') + "/local-config/ai-provider.properties");
        }

        Files.writeString(dataDir.resolve("application-local.yml"), """
                server:
                  port: 9000
                """);
        try (ConfigurableApplicationContext ctx = start()) {
            assertThat(ctx.getEnvironment().getProperty("server.port")).isEqualTo("9000");
            assertThat(ctx.getEnvironment().getProperty("rpsim.desktop.open-browser")).isEqualTo("false");
        }
    }

    @Test
    void noSetupFileKeepsTheDefaults() {
        try (ConfigurableApplicationContext ctx = start()) {
            assertThat(ctx.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(ctx.getEnvironment().getProperty("rpsim.desktop.open-browser")).isEqualTo("true");
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                // as the launcher's system properties: desktop last (beats the test profile), data folder over application.yml
                .run("--spring.profiles.active=desktop", "--rpsim.desktop.data-dir=" + dataDir.toString().replace('\\', '/'));
    }
}
