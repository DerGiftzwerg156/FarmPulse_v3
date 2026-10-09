package de.farmpulse.rpsim.lan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import de.farmpulse.rpsim.domain.LanSettings;
import de.farmpulse.rpsim.repository.LanSettingsRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Roadmap V3 R3-N1/N2: access by sender address, optional PIN, session cookie, lock and the game-PC-only settings. */
@SpringBootTest
@AutoConfigureMockMvc
class LanAccessTest {

    static final String TABLET = "192.168.178.20";
    static final String INTERNET = "8.8.8.8";
    static final String PIN = "4711";
    static Path web;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        web = Files.createTempDirectory("rpsim-web-lan");
        Files.writeString(web.resolve("index.html"), "<app-root>FarmPulse</app-root>");
        r.add("rpsim.web.static-dir", () -> web.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired LanAccessService lan;
    @Autowired LanSettingsRepository settings;

    Instant now = Instant.parse("2026-09-30T12:00:00Z");

    @BeforeEach
    void reset() {
        lan.setClock(Clock.fixed(now, ZoneOffset.UTC));
        lan.removePin();
        lan.setEnabled(false);
    }

    @AfterEach
    void restoreClock() {
        lan.setClock(Clock.systemUTC());
    }

    private static RequestPostProcessor from(String address) {
        return req -> {
            req.setRemoteAddr(address);
            return req;
        };
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Cookie login(String pin) throws Exception {
        var res = mvc.perform(json(post("/api/lan/login"), "{\"pin\":\"" + pin + "\"}").with(from(TABLET)))
                .andExpect(status().isOk()).andReturn().getResponse();
        String header = res.getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly").contains("SameSite=Strict").contains("Max-Age=" + 30 * 24 * 3600);
        return res.getCookie(lan.cookieName());
    }

    @Test
    void theGamingPcAlwaysGetsInWithoutPin() throws Exception {
        lan.setPin(PIN);
        mvc.perform(get("/api/settings/ai")).andExpect(status().isOk());
        mvc.perform(get("/api/lan/status")).andExpect(jsonPath("$.gamePc").value(true))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void withTheSwitchOffTheHomeNetworkGets403() throws Exception {
        mvc.perform(get("/api/settings/ai").with(from(TABLET))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LAN_FORBIDDEN"));
        mvc.perform(get("/index.html").with(from(TABLET))).andExpect(status().isForbidden());
        mvc.perform(get("/api/lan/status").with(from(TABLET))).andExpect(status().isForbidden());
    }

    @Test
    void theInternetIsAlwaysRefused() throws Exception {
        lan.setEnabled(true);
        mvc.perform(get("/api/settings/ai").with(from(INTERNET))).andExpect(status().isForbidden());
        mvc.perform(get("/index.html").with(from(INTERNET))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/lan/login"), "{\"pin\":\"4711\"}").with(from("2001:db8::1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutPinTheHomeNetworkGetsInDirectly() throws Exception {
        lan.setEnabled(true);
        mvc.perform(get("/api/settings/ai").with(from(TABLET))).andExpect(status().isOk());
        mvc.perform(get("/api/lan/status").with(from(TABLET))).andExpect(jsonPath("$.gamePc").value(false))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.pinSet").value(false));
        mvc.perform(json(post("/api/lan/login"), "{\"pin\":\"1\"}").with(from(TABLET)))
                .andExpect(jsonPath("$.result").value("NO_PIN"));
    }

    @Test
    void withPinTheApiAndLiveUpdatesNeedTheSessionCookie() throws Exception {
        lan.setEnabled(true);
        lan.setPin(PIN);
        mvc.perform(get("/api/settings/ai").with(from(TABLET))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LAN_LOGIN_REQUIRED"));
        mvc.perform(get("/api/events/stream").with(from(TABLET))).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health").with(from(TABLET))).andExpect(status().isUnauthorized());
        // the web app and the login stay reachable
        mvc.perform(get("/index.html").with(from(TABLET))).andExpect(status().isOk())
                .andExpect(content().string("<app-root>FarmPulse</app-root>"));
        mvc.perform(get("/api/lan/status").with(from(TABLET))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(json(post("/api/lan/login"), "{\"pin\":\"0000\"}").with(from(TABLET)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.result").value("WRONG_PIN"));

        Cookie session = login(PIN);
        mvc.perform(get("/api/settings/ai").with(from(TABLET)).cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/lan/status").with(from(TABLET)).cookie(session))
                .andExpect(jsonPath("$.authenticated").value(true));
        // logout ends the session
        mvc.perform(post("/api/lan/logout").with(from(TABLET)).cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/settings/ai").with(from(TABLET)).cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheGamingPcChangesSwitchAndPin() throws Exception {
        mvc.perform(json(put("/api/lan/settings"), "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(json(put("/api/lan/pin"), "{\"pin\":\"" + PIN + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pinSet").value(true));
        Cookie session = login(PIN);
        mvc.perform(json(put("/api/lan/settings"), "{\"enabled\":false}").with(from(TABLET)).cookie(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LAN_GAME_PC_ONLY"));
        mvc.perform(json(put("/api/lan/pin"), "{\"pin\":\"9999\"}").with(from(TABLET)).cookie(session))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/lan/pin").with(from(TABLET)).cookie(session)).andExpect(status().isForbidden());
        assertThat(lan.enabled()).isTrue();
        // the gaming PC removes the PIN: the tablet gets in without login
        mvc.perform(delete("/api/lan/pin")).andExpect(jsonPath("$.pinSet").value(false));
        mvc.perform(get("/api/settings/ai").with(from(TABLET))).andExpect(status().isOk());
    }

    @Test
    void thePinIsFourToEightDigitsAndStoredOnlyAsHash() throws Exception {
        for (String bad : new String[] {"123", "123456789", "12a4", ""}) {
            mvc.perform(json(put("/api/lan/pin"), "{\"pin\":\"" + bad + "\"}")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAN_PIN_INVALID"));
        }
        mvc.perform(json(put("/api/lan/pin"), "{\"pin\":\"12345678\"}")).andExpect(status().isOk());
        LanSettings row = settings.findById(LanSettings.ID).orElseThrow();
        assertThat(row.getPinHash()).isNotBlank().doesNotContain("12345678");
        assertThat(row.getPinSalt()).isNotBlank();
        assertThat(row.getPinIterations()).isEqualTo(1000); // test profile; 600,000 in application.yml
    }

    @Test
    void tooManyWrongPinsLockTheSenderForFiveMinutes() throws Exception {
        lan.setEnabled(true);
        lan.setPin(PIN);
        for (int i = 0; i < 4; i++) {
            assertThat(lan.login("0000", TABLET).result()).isEqualTo(LanAccessService.LoginResult.WRONG_PIN);
        }
        assertThat(lan.login("0000", TABLET).result()).isEqualTo(LanAccessService.LoginResult.LOCKED);
        // even the right PIN is refused while locked; another device is not affected
        mvc.perform(json(post("/api/lan/login"), "{\"pin\":\"" + PIN + "\"}").with(from(TABLET)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.result").value("LOCKED"))
                .andExpect(jsonPath("$.lockedSeconds").value(300));
        assertThat(lan.login(PIN, "192.168.178.21").result()).isEqualTo(LanAccessService.LoginResult.OK);
        lan.setClock(Clock.fixed(now.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        assertThat(lan.login(PIN, TABLET).result()).isEqualTo(LanAccessService.LoginResult.OK);
    }

    @Test
    void sessionsEndAfter30DaysWithANewPinAndWhenSwitchedOff() throws Exception {
        lan.setEnabled(true);
        lan.setPin(PIN);
        String token = lan.login(PIN, TABLET).token();
        assertThat(lan.sessionValid(token)).isTrue();
        lan.setClock(Clock.fixed(now.plus(Duration.ofDays(30)), ZoneOffset.UTC));
        assertThat(lan.sessionValid(token)).isFalse();

        lan.setClock(Clock.fixed(now, ZoneOffset.UTC));
        token = lan.login(PIN, TABLET).token();
        lan.setPin("2468");
        assertThat(lan.sessionValid(token)).isFalse();

        token = lan.login("2468", TABLET).token();
        lan.setEnabled(false);
        assertThat(lan.sessionValid(token)).isFalse();
    }

    /** Review 10/2026 Phase 0.4 (S-2): the AI key goes to the configured address - only the gaming PC may change it. */
    @Test
    void aiSettingsAreReadOnlyOnATablet() throws Exception {
        lan.setEnabled(true);
        mvc.perform(get("/api/settings/ai").with(from(TABLET))).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(json(put("/api/settings/ai"), "{\"provider\":\"OPENAI\",\"baseUrl\":\"https://evil.example\"}")
                        .with(from(TABLET)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LAN_GAME_PC_ONLY"))
                .andExpect(jsonPath("$.message").value("Nur am Spiele-PC änderbar"));
        mvc.perform(get("/api/settings/ai")).andExpect(jsonPath("$.editable").value(true));
    }
}
