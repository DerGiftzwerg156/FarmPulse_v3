package de.farmpulse.rpsim.lan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Review 10/2026 Phase 0.2/0.3 (S-2): a web page re-pointed at 127.0.0.1 by DNS rebinding reaches the backend from
 * loopback - it is stopped by its Host and Origin header.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HostHeaderFilterTest {

    static final String ATTACK = "{\"provider\":\"OPENAI\",\"baseUrl\":\"https://evil.example/v1\"}";

    @Autowired
    MockMvc mvc;

    @Test
    void dnsRebindingCannotRedirectTheAiKey() throws Exception {
        String before = mvc.perform(get("/api/settings/ai")).andReturn().getResponse().getContentAsString();

        mvc.perform(put("/api/settings/ai").header("Host", "evil.example:8080").header("Origin", "http://evil.example:8080")
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACK))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOST_FORBIDDEN"));
        mvc.perform(get("/api/settings/ai").header("Host", "evil.example:8080"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOST_FORBIDDEN"));
        mvc.perform(get("/api/events/stream").header("Host", "evil.example:8080")).andExpect(status().isForbidden());

        mvc.perform(get("/api/settings/ai")).andExpect(result ->
                org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).isEqualTo(before));
    }

    @Test
    void allowedHostsPass() throws Exception {
        mvc.perform(get("/api/settings/ai").header("Host", "localhost:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/settings/ai").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/settings/ai").header("Host", "[::1]:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/settings/ai")).andExpect(status().isOk()); // no Host header: no browser
    }

    @Test
    void writingRequestsOfAForeignPageAreRefused() throws Exception {
        mvc.perform(post("/api/lan/logout").header("Host", "localhost:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"));
        mvc.perform(put("/api/settings/ai").header("Host", "localhost:8080").header("Origin", "null")
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACK))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"));
    }

    @Test
    void ownOriginsMayWrite() throws Exception {
        // Angular dev server (proxy, changeOrigin) and the release build on port 8080
        mvc.perform(put("/api/settings/ai").header("Host", "localhost:8080").header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"NONE\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/settings/ai").header("Host", "localhost:8080").header("Origin", "http://localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"NONE\"}"))
                .andExpect(status().isOk());
    }
}
