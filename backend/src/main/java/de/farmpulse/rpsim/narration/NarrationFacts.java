package de.farmpulse.rpsim.narration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ONLY payload type the personality layer receives: finished, immutable facts of the fact layer.
 * <p>
 * Two-tier principle (technical concept "Vier Bausteine im Prompt"): the AI narrates decided results, it never
 * sees internal formula values. Keys that would expose internals (scores, minimum prices, NPC limits, trust
 * values) are rejected at construction time - a reviewer can verify this in one place.
 */
public final class NarrationFacts {

    /** Internal formula values that must never reach the AI (lower-case substring match). */
    static final Set<String> FORBIDDEN_KEY_PARTS = Set.of("score", "minaccept", "maxbid", "trust", "weight",
            "threshold", "hiddenmax", "virtualwealth");

    private final Map<String, Object> values;

    private NarrationFacts(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public static boolean isForbiddenKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return FORBIDDEN_KEY_PARTS.stream().anyMatch(k::contains);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            if (isForbiddenKey(key)) {
                throw new IllegalArgumentException("Fact key '" + key + "' would expose an internal formula value to the AI");
            }
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Enum<?>)) {
                throw new IllegalArgumentException("Facts only carry primitive values, got " + value.getClass());
            }
            if (value != null) {
                values.put(key, value instanceof Enum<?> e ? e.name() : value);
            }
            return this;
        }

        public NarrationFacts build() {
            return new NarrationFacts(new LinkedHashMap<>(values));
        }
    }
}
