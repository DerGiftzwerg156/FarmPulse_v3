package de.farmpulse.rpsim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Logic review LR-H2: the levels the backend can write are exactly the levels the mod accepts
 * ({@code RPSimInstructions.NOTIFICATION_LEVELS}) and the bridge simulator's schema allows.
 */
class NotificationLevelContractTest {

    private static final Path REPO = Path.of("..");

    private static List<String> enumNames() {
        return Arrays.stream(NotificationLevel.values()).map(Enum::name).sorted().toList();
    }

    @Test
    void matchesTheModsAcceptedLevels() throws Exception {
        String lua = Files.readString(REPO.resolve("mod/FS25_RPSim/src/import/Instructions.lua"));
        Matcher m = Pattern.compile("NOTIFICATION_LEVELS\\s*=\\s*\\{([^}]*)}").matcher(lua);
        assertThat(m.find()).isTrue();
        List<String> mod = Pattern.compile("([A-Z_]+)\\s*=\\s*true").matcher(m.group(1)).results()
                .map(r -> r.group(1)).sorted().toList();
        assertThat(mod).isEqualTo(enumNames());
    }

    @Test
    void matchesTheSimulatorSchema() throws Exception {
        JsonNode schema = JsonMapper.builder().build()
                .readTree(Files.readString(REPO.resolve("tools/bridge-simulator/schema/instructions.schema.json")));
        List<String> levels = schema.findValues("level").stream()
                .filter(n -> n.has("enum"))
                .flatMap(n -> n.get("enum").valueStream())
                .map(JsonNode::asString).distinct().sorted().toList();
        assertThat(levels).isEqualTo(enumNames());
    }
}
