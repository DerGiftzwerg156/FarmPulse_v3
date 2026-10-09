package de.farmpulse.rpsim.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import de.farmpulse.rpsim.common.OwnerOnlyFiles;
import de.farmpulse.rpsim.config.RpsimProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Effective AI configuration: application(-local).yml defaults, overridden by the local, git-ignored properties
 * file written by the settings page. "Own API key per player" is a concept decision - keys are never committed and
 * never returned to the frontend.
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

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

    /**
     * Stores provider selection (and optionally model/key/baseUrl) in the local file. A null key keeps the old key -
     * unless the host of the base URL changes (review 10/2026 Phase 0.4, S-2): a stored key is only ever sent to the
     * host it was entered for, so a new host without a new key discards it (an empty entry also hides a key from
     * application(-local).yml).
     */
    public synchronized View save(String provider, String model, String apiKey, String baseUrl) {
        Properties p = local();
        String id = provider.toUpperCase(Locale.ROOT);
        p.setProperty("provider", id);
        String key = id.toLowerCase(Locale.ROOT);
        String previousBaseUrl = settings(id).baseUrl();
        if (model != null && !model.isBlank()) {
            p.setProperty(key + ".model", model.strip());
        }
        boolean newKey = apiKey != null && !apiKey.isBlank();
        if (newKey) {
            p.setProperty(key + ".apiKey", apiKey.strip());
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            p.setProperty(key + ".baseUrl", baseUrl.strip());
            if (!newKey && !sameHost(previousBaseUrl, baseUrl.strip())) {
                p.setProperty(key + ".apiKey", "");
            }
        }
        try {
            StringWriter out = new StringWriter();
            p.store(out, "Local AI provider settings - never commit this file");
            OwnerOnlyFiles.write(file(), out.toString().getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new IllegalStateException("Could not store local AI settings: " + e.getMessage(), e);
        }
        return view();
    }

    /** Same host (case-insensitive); unparsable URLs never count as the same host. */
    static boolean sameHost(String a, String b) {
        String ha = host(a);
        String hb = host(b);
        return ha != null && ha.equalsIgnoreCase(hb);
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new URI(url.strip()).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Review 10/2026 Phase 0.5 (S-3): a file written by an older version gets owner-only permissions as well. */
    @PostConstruct
    void restrictExistingFile() {
        if (Files.isRegularFile(file())) {
            try {
                OwnerOnlyFiles.restrict(file());
            } catch (IOException e) {
                log.warn("Could not restrict the permissions of {}: {}", file().toAbsolutePath(), e.getMessage());
            }
        }
    }

    public int timeoutSeconds() {
        return props.getAi().getTimeoutSeconds();
    }
}
