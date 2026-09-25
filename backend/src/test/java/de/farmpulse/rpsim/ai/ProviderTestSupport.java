package de.farmpulse.rpsim.ai;

import java.nio.file.Path;

import de.farmpulse.rpsim.config.RpsimProperties;
import tools.jackson.databind.json.JsonMapper;

final class ProviderTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AiPrompt PROMPT = new AiPrompt("SYSTEM-TEXT", "USER-TEXT");

    private ProviderTestSupport() {
    }

    /** Settings service backed by a temp local config file. */
    static AiSettingsService settings(String provider, String apiKey) {
        RpsimProperties p = new RpsimProperties();
        p.getAi().setLocalConfigFile(Path.of(System.getProperty("java.io.tmpdir"),
                "rpsim-ai-" + System.nanoTime() + ".properties").toString());
        AiSettingsService s = new AiSettingsService(p);
        s.save(provider, null, apiKey, null);
        return s;
    }
}
