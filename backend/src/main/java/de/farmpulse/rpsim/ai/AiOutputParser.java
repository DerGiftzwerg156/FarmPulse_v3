package de.farmpulse.rpsim.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Extracts the {"subject","body"} JSON object from a model answer (tolerates code fences / leading text). */
public final class AiOutputParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AiOutputParser() {
    }

    public static AiResult parse(String text) {
        if (text == null) {
            throw new AiProviderException("empty model output");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiProviderException("model output contains no JSON object");
        }
        try {
            JsonNode n = JSON.readTree(text.substring(start, end + 1));
            String subject = n.path("subject").asString("");
            String body = n.path("body").asString("");
            if (body.isBlank()) {
                throw new AiProviderException("model output has no body");
            }
            return new AiResult(subject.strip(), body.strip());
        } catch (AiProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiProviderException("model output is not valid JSON: " + e.getMessage(), e);
        }
    }
}
