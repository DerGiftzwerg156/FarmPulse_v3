package de.farmpulse.rpsim.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Release mode: the backend serves the built frontend with an SPA fallback, the API stays untouched. */
@SpringBootTest
@AutoConfigureMockMvc
class StaticFrontendTest {

    static Path web;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        web = Files.createTempDirectory("rpsim-web");
        Files.writeString(web.resolve("index.html"), "<app-root>FarmPulse</app-root>");
        Files.writeString(web.resolve("main.js"), "console.log('app')");
        r.add("rpsim.web.static-dir", () -> web.toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void servesFilesAndFallsBackToIndexForClientRoutes() throws Exception {
        mvc.perform(get("/main.js")).andExpect(status().isOk()).andExpect(content().string("console.log('app')"));
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html")); // MockMvc does not follow forwards
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string("<app-root>FarmPulse</app-root>"));
        mvc.perform(get("/mailbox")).andExpect(status().isOk()).andExpect(content().string("<app-root>FarmPulse</app-root>"));
        mvc.perform(get("/missing.png")).andExpect(status().isNotFound());
    }

    @Test
    void apiIsNotShadowed() throws Exception {
        mvc.perform(get("/api/settings/ai")).andExpect(status().isOk());
    }
}
