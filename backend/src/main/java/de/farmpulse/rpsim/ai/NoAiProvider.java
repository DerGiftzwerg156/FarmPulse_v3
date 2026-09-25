package de.farmpulse.rpsim.ai;

import org.springframework.stereotype.Component;

/** Provider NONE: no AI configured - every narration uses the fallback templates (game stays fully playable). */
@Component
public class NoAiProvider implements AiProvider {

    @Override
    public String id() {
        return "NONE";
    }

    @Override
    public AiResult generate(AiPrompt prompt) {
        throw new AiProviderException("Kein KI-Anbieter konfiguriert");
    }
}
