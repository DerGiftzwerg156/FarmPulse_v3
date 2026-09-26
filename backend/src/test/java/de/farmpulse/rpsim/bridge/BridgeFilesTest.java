package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BridgeFilesTest {

    @Test
    void xmlPayloadWrapsAndEscapesJsonForTheModsXmlReader() {
        String xml = BridgeFiles.xmlPayload("{\"note\":\"Weizen & Gerste <Mai>\"}");
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n<rpsim>\n");
        assertThat(xml).contains("<json>{\"note\":\"Weizen &amp; Gerste &lt;Mai&gt;\"}</json>");
        assertThat(xml).endsWith("</rpsim>\n");
    }
}
