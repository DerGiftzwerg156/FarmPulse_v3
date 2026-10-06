package de.farmpulse.rpsim.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.farmpulse.rpsim.repository.AppHintSeenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** First-open hints of the apps (owner decision 2026-10-06): remembered per installation, without a savegame. */
@SpringBootTest
@AutoConfigureMockMvc
class AppHintTest {

    @Autowired MockMvc mvc;
    @Autowired AppHintSeenRepository repo;

    @BeforeEach
    void reset() {
        repo.deleteAll();
    }

    @Test
    void noHintIsSeenAtFirst() throws Exception {
        mvc.perform(get("/api/app-hints")).andExpect(status().isOk()).andExpect(jsonPath("$.seen").isEmpty());
    }

    @Test
    void aConfirmedHintStaysSeenAndRepeatingIsHarmless() throws Exception {
        mvc.perform(put("/api/app-hints/bank")).andExpect(status().isOk()).andExpect(jsonPath("$.seen[0]").value("bank"));
        mvc.perform(put("/api/app-hints/bank")).andExpect(status().isOk()).andExpect(jsonPath("$.seen.length()").value(1));
        mvc.perform(put("/api/app-hints/market")).andExpect(status().isOk());
        mvc.perform(get("/api/app-hints")).andExpect(jsonPath("$.seen[0]").value("bank"))
                .andExpect(jsonPath("$.seen[1]").value("market"));
    }

    @Test
    void anInvalidAppIdIsRefused() throws Exception {
        mvc.perform(put("/api/app-hints/Bank-1")).andExpect(status().isBadRequest());
    }
}
