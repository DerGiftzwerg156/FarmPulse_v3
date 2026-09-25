package de.farmpulse.rpsim.tone;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.ToneClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic, lexicon-based tone classifier (friendly / neutral / rude) - explicitly NO AI call
 * (functional concept "Freie Antworten des Spielers"). Also flags mechanical wishes in free text
 * ("give me a better rate"); such a flag never changes a formula, it only tells the prompt to politely redirect the
 * player to the official form-based process.
 */
@Component
public class ToneClassifier {

    public record Lexicon(List<String> friendly, List<String> rude, List<String> mechanicalRequestTopics,
                          List<String> mechanicalRequestVerbs) {
    }

    public record Result(ToneClass tone, int friendlyHits, int rudeHits, boolean mechanicalRequest) {
    }

    private static final Pattern WS = Pattern.compile("\\s+");

    private final Lexicon lexicon;

    public ToneClassifier(RpsimProperties props, JsonMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("tone-lexicon/" + props.getAi().getLocale() + ".json").getInputStream()) {
            this.lexicon = json.readValue(in, Lexicon.class);
        }
    }

    static String normalize(String text) {
        return WS.matcher(text == null ? "" : text.toLowerCase(Locale.GERMAN)).replaceAll(" ").trim();
    }

    private static int hits(String text, List<String> words) {
        int n = 0;
        for (String w : words) {
            int idx = text.indexOf(w);
            while (idx >= 0) {
                n++;
                idx = text.indexOf(w, idx + w.length());
            }
        }
        return n;
    }

    public Result classify(String text) {
        String t = normalize(text);
        int friendly = hits(t, lexicon.friendly());
        int rude = hits(t, lexicon.rude());
        // shouting (long all-caps text) counts as rude
        String letters = text == null ? "" : text.replaceAll("[^\\p{L}]", "");
        if (letters.length() >= 12 && letters.equals(letters.toUpperCase(Locale.GERMAN))) {
            rude++;
        }
        ToneClass tone;
        if (rude > 0 && rude >= friendly) {
            tone = ToneClass.RUDE;
        } else if (friendly > rude) {
            tone = ToneClass.FRIENDLY;
        } else {
            tone = ToneClass.NEUTRAL;
        }
        boolean mechanical = hits(t, lexicon.mechanicalRequestTopics()) > 0 && hits(t, lexicon.mechanicalRequestVerbs()) > 0;
        return new Result(tone, friendly, rude, mechanical);
    }

    public List<String> lexiconSizes() {
        List<String> l = new ArrayList<>();
        l.add("friendly=" + lexicon.friendly().size());
        l.add("rude=" + lexicon.rude().size());
        return l;
    }
}
