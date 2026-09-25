package de.farmpulse.rpsim.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** Strategy selection: the active provider follows the (local) configuration at call time. */
@Component
public class AiProviderRegistry {

    private final Map<String, AiProvider> providers;
    private final AiSettingsService settings;

    public AiProviderRegistry(List<AiProvider> providers, AiSettingsService settings) {
        this.providers = providers.stream().collect(Collectors.toMap(p -> p.id().toUpperCase(Locale.ROOT),
                Function.identity()));
        this.settings = settings;
    }

    public AiProvider active() {
        return providers.getOrDefault(settings.activeProvider(), providers.get("NONE"));
    }

    public AiProvider byId(String id) {
        AiProvider p = providers.get(id.toUpperCase(Locale.ROOT));
        if (p == null) {
            throw new IllegalArgumentException("unknown provider " + id);
        }
        return p;
    }

    public List<String> ids() {
        return providers.keySet().stream().sorted().toList();
    }
}
