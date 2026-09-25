package de.farmpulse.rpsim.ai;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.onboarding.FreeTextModerator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Light content check for the onboarding free-text field ONLY (functional concept: "Eine leichte Inhaltsprüfung
 * filtert Missbrauchsversuche"). Rejects prompt-injection attempts, attempts to talk numbers into existence and
 * clearly abusive content. A hit makes the onboarding treat the field as empty (silent fallback, no blocking).
 */
@Component
public class LexiconFreeTextModerator implements FreeTextModerator {

    public record Lexicon(List<String> injection, List<String> numberManipulation, List<String> abuse) {
    }

    private final Lexicon lexicon;

    public LexiconFreeTextModerator(RpsimProperties props, JsonMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("moderation/" + props.getAi().getLocale() + ".json").getInputStream()) {
            this.lexicon = json.readValue(in, Lexicon.class);
        }
    }

    @Override
    public boolean accept(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.toLowerCase(Locale.GERMAN).replaceAll("\\s+", " ");
        return !(containsAny(t, lexicon.injection()) || containsAny(t, lexicon.numberManipulation())
                || containsAny(t, lexicon.abuse()));
    }

    private static boolean containsAny(String t, List<String> words) {
        return words.stream().anyMatch(t::contains);
    }
}
