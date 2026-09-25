package de.farmpulse.rpsim.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.springframework.stereotype.Service;

/**
 * Effective AI configuration: application(-local).yml defaults, overridden by the local, git-ignored properties
 * file written by the settings page. "Own API key per player" is a concept decision - keys are never committed and
 * never returned to the frontend.
 */
@Service
public class AiSettingsService {

    public record ProviderSettings(String baseUrl, String model, String apiKey) {
    }

    public record View(String provider, String model, String baseUrl, boolean apiKeySet) {
    }

    private final RpsimProperties props;

    public AiSettingsService(RpsimProperties props) {
        this.props = props;
    }

    private Path file() {
        return Path.of(props.getAi().getLocalConfigFile());
    }

    synchronized Properties local() {
        Properties p = new Properties();
        if (Files.isRegularFile(file())) {
            try (InputStream in = Files.newInputStream(file())) {
                p.load(in);
            } catch (IOException e) {
                // unreadable local config -> defaults
            }
        }
        return p;
    }

    public String activeProvider() {
        return local().getProperty("provider", props.getAi().getProvider()).toUpperCase(Locale.ROOT);
    }

    public ProviderSettings settings(String provider) {
        String key = provider.toLowerCase(Locale.ROOT);
        RpsimProperties.Provider def = switch (key) {
            case "openai" -> props.getAi().getOpenai();
            case "anthropic" -> props.getAi().getAnthropic();
            case "gemini" -> props.getAi().getGemini();
            case "ollama" -> props.getAi().getOllama();
            default -> new RpsimProperties.Provider("", "");
        };
        Properties p = local();
        return new ProviderSettings(p.getProperty(key + ".baseUrl", def.getBaseUrl()),
                p.getProperty(key + ".model", def.getModel()), p.getProperty(key + ".apiKey", def.getApiKey()));
    }

    public View view() {
        String provider = activeProvider();
        ProviderSettings s = settings(provider);
        return new View(provider, s.model(), s.baseUrl(), s.apiKey() != null && !s.apiKey().isBlank());
    }

    /** Stores provider selection (and optionally model/key/baseUrl) in the local file. A null key keeps the old key. */
    public synchronized View save(String provider, String model, String apiKey, String baseUrl) {
        Properties p = local();
        String id = provider.toUpperCase(Locale.ROOT);
        p.setProperty("provider", id);
        String key = id.toLowerCase(Locale.ROOT);
        if (model != null && !model.isBlank()) {
            p.setProperty(key + ".model", model.strip());
        }
        if (apiKey != null && !apiKey.isBlank()) {
            p.setProperty(key + ".apiKey", apiKey.strip());
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            p.setProperty(key + ".baseUrl", baseUrl.strip());
        }
        try {
            Files.createDirectories(file().toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "Local AI provider settings - never commit this file");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not store local AI settings: " + e.getMessage(), e);
        }
        return view();
    }

    public int timeoutSeconds() {
        return props.getAi().getTimeoutSeconds();
    }
}
