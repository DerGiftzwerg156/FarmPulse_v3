package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** AP-10.4: docs/dev/configuration-reference.md must list every leaf key under rpsim.* of application.yml. */
class ConfigurationReferenceDocTest {

    private static final Pattern KEY = Pattern.compile("^(\\s*)([\\w-]+):\\s*(.*)$");

    static List<String> leafKeys(List<String> yaml) {
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        List<String> keys = new ArrayList<>();
        for (String line : yaml) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            Matcher m = KEY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                names.pop();
            }
            indents.push(new int[]{indent});
            names.push(m.group(2));
            String value = m.group(3).strip();
            boolean leaf = !value.isEmpty() && !value.startsWith("#");
            List<String> path = new ArrayList<>(names);
            java.util.Collections.reverse(path);
            if (leaf && path.get(0).equals("rpsim")) {
                keys.add(String.join(".", path));
            }
        }
        return keys;
    }

    @Test
    void everyKeyIsDocumented() throws IOException {
        List<String> yaml = Files.readAllLines(Path.of("src/main/resources/application.yml"));
        String doc = Files.readString(Path.of("../docs/dev/configuration-reference.md"));
        List<String> keys = leafKeys(yaml);
        assertThat(keys).hasSizeGreaterThan(200);
        assertThat(keys).allSatisfy(k -> assertThat(doc).as("documented: %s", k).contains("`" + k + "`"));
    }
}
