package de.farmpulse.rpsim.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Low level access to the bridge folder. Readers are defensive: unreadable/partial/invalid documents are
 * skipped with a warning and retried on the next cycle - never a crash.
 */
@Component
public class BridgeFiles {

    private static final Logger log = LoggerFactory.getLogger(BridgeFiles.class);

    private final RpsimProperties props;
    private final JsonMapper json;

    public BridgeFiles(RpsimProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    public Path base() {
        return Path.of(props.getBridge().getPath());
    }

    public Path farmFacts() {
        return base().resolve("export").resolve("farm_facts.json");
    }

    public Path marketContext() {
        return base().resolve("export").resolve("market_context.json");
    }

    public Path instructions() {
        return base().resolve("import").resolve("instructions.json");
    }

    /**
     * The instructions as the mod reads them: FS25 blocks {@code io.open} in read mode, so the mod can only read
     * XML files (engine XML API). The JSON document is wrapped in {@code <rpsim><json>...</json></rpsim>}.
     */
    public Path instructionsXml() {
        return base().resolve("import").resolve("instructions.xml");
    }

    /** Roadmap V2 R2-F1: answers of the yes/no questions, written by the mod right after every answer. */
    public Path playerResponses() {
        return base().resolve("export").resolve("player_responses.json");
    }

    public Path ack() {
        return base().resolve("import").resolve("instructions_ack.json");
    }

    /** Result of a read: the raw text (for hashing/storage) and the parsed document. */
    public record Read<T>(String raw, T doc) {
    }

    public <T> Optional<Read<T>> read(Path path, Class<T> type, Function<T, List<String>> validator) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return Optional.empty();
            }
            T doc = json.readValue(raw, type);
            List<String> errors = validator.apply(doc);
            if (!errors.isEmpty()) {
                log.warn("Skipping invalid bridge file {} (retry next cycle): {}", path.getFileName(), errors);
                return Optional.empty();
            }
            return Optional.of(new Read<>(raw, doc));
        } catch (IOException | JacksonException e) {
            log.warn("Skipping unreadable bridge file {} (retry next cycle): {}", path.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Atomic write (tmp file + atomic move); falls back to a plain replace if atomic moves are unsupported. */
    public void writeAtomic(Path path, Object doc) {
        try {
            writeTextAtomic(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        } catch (IOException | JacksonException e) {
            log.warn("Could not write bridge file {}: {}", path, e.getMessage());
        }
    }

    /** Atomically writes the document as compact JSON inside the XML wrapper the mod reads (see instructionsXml). */
    public void writeAtomicXmlPayload(Path path, Object doc) {
        try {
            writeTextAtomic(path, xmlPayload(json.writeValueAsString(doc)));
        } catch (IOException | JacksonException e) {
            log.warn("Could not write bridge file {}: {}", path, e.getMessage());
        }
    }

    /** Wraps JSON text in {@code <rpsim><json>...</json></rpsim>}; the mod reads it with getString("rpsim.json"). */
    static String xmlPayload(String jsonText) {
        String escaped = jsonText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n<rpsim>\n    <json>" + escaped
                + "</json>\n</rpsim>\n";
    }

    private static void writeTextAtomic(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
