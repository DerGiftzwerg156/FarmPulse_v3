package de.farmpulse.rpsim.narration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.farmpulse.rpsim.ai.AiResult;
import de.farmpulse.rpsim.config.RpsimProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Prepared, generic fallback texts per event type ("generische, vorgefertigte Fallback-Texte"), stored
 * locale-keyed under {@code fallback-templates/{locale}/{TYPE}.txt} (first line = subject). V1 ships {@code de} only.
 */
@Component
public class FallbackTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<NarrationEventType, String[]> templates = new EnumMap<>(NarrationEventType.class);
    private final Properties labels = new Properties();
    private final Locale locale;

    public FallbackTemplates(RpsimProperties props) throws IOException {
        String loc = props.getAi().getLocale();
        this.locale = Locale.forLanguageTag(loc);
        for (NarrationEventType t : NarrationEventType.values()) {
            ClassPathResource r = new ClassPathResource("fallback-templates/" + loc + "/" + t.name() + ".txt");
            try (InputStream in = r.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int nl = text.indexOf('\n');
                templates.put(t, new String[]{text.substring(0, nl).strip(), text.substring(nl + 1).strip()});
            }
        }
        try (InputStream in = new ClassPathResource("fallback-templates/" + loc + "/_labels.properties").getInputStream()) {
            labels.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    public boolean has(NarrationEventType type) {
        return templates.containsKey(type);
    }

    public AiResult render(NarrationEventType type, Map<String, Object> facts, String characterName) {
        String[] t = templates.get(type);
        String subject = fill(t[0], facts, characterName).strip();
        if (subject.isEmpty() || subject.endsWith(":")) {
            subject = "Nachricht von " + (characterName == null ? "der Dorfgemeinschaft" : characterName);
        }
        String body = fill(t[1], facts, characterName);
        if (Boolean.TRUE.equals(facts.get("absenceNote"))) {
            body = "(Automatische Abwesenheitsnotiz: " + (characterName == null ? "Die zuständige Person" : characterName)
                    + " ist derzeit nicht im Haus – die Antwort erfolgt verzögert.)\n\n" + body;
        }
        return new AiResult(subject, body);
    }

    private String fill(String text, Map<String, Object> facts, String characterName) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = "characterName".equals(key) ? (characterName == null ? "Ihre Dorfgemeinschaft" : characterName)
                    : facts.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(format(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String format(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number n && !(v instanceof Double d && d % 1 != 0)) {
            return NumberFormat.getIntegerInstance(locale).format(n.longValue());
        }
        String s = v.toString();
        return labels.getProperty(s, s);
    }
}
