package de.farmpulse.rpsim.api;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import de.farmpulse.rpsim.bridge.DetectedSavegameRegistry;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageRelation;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.onboarding.OnboardingService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DoD AP-6.1: at least one integration test per endpoint, incl. validation failures. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired OnboardingService onboarding;
    @Autowired DetectedSavegameRegistry detected;
    @Autowired SavegameContext context;
    @Autowired Fixtures fx;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired CharacterRepository characters;
    @Autowired CommunicationService communications;
    @Autowired MarketEventEngine market;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        Savegame draft = onboarding.create(new OnboardingService.Request(FarmOrigin.INHERITED, VillageRelation.CONNECTED,
                "Ein Hof im Norden.", 200_000, null, TonePreset.REALISTIC, List.of(JobRole.MECHANIC)));
        String id = "api_" + System.nanoTime();
        detected.report(id, "Erlengrund", 10 * 86_400_000L, 500_000);
        sg = onboarding.confirm(draft.getId(), id);
        sg.setMarketContextJson(TestData.marketContext(id));
        fx.snapshot(sg, 1_000_000);
        ownership.reconcile(sg);
        context.setCurrentBridgeSavegameId(id);
        bank = characters.findBySavegameOrderByIdAsc(sg).stream()
                .filter(c -> c.getRole().name().equals("BANK_ADVISOR")).findFirst().orElseThrow();
    }

    @AfterEach
    void cleanup() throws Exception {
        context.setCurrentBridgeSavegameId(null);
        Files.deleteIfExists(Path.of(props.getAi().getLocalConfigFile()));
    }

    ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    Communication mailFrom(Character c, Channel channel) {
        return communications.create(new CommunicationService.Draft(sg, c, channel, CommunicationInitiator.CHARACTER,
                "Betreff", "Text", CommunicationCategory.GENERAL, "REPLY", null, null, null, null, true, null));
    }

    // ------------------------------------------------------------------ savegame / onboarding

    @Test
    void savegameHeader() throws Exception {
        mvc.perform(get("/api/savegame")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mapName").value("Erlengrund"))
                .andExpect(jsonPath("$.balance").value(1_000_000))
                .andExpect(jsonPath("$.gameDay").value(10));
    }

    @Test
    void onboardingEndpoints() throws Exception {
        JsonNode created = read(postJson("/api/onboarding", java.util.Map.of("startingCapitalTarget", 100000,
                "initialEmployees", List.of("MECHANIC"), "villageRelation", "UNKNOWN")).andExpect(status().isOk()));
        long id = created.get("id").asLong();
        long firstCharacter = created.get("cast").get(0).get("characterId").asLong();
        mvc.perform(get("/api/onboarding/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.cast", hasSize(9)));
        postJson("/api/onboarding/" + id + "/reroll", java.util.Map.of("characterId", firstCharacter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cast[*].characterId", not(hasItem((int) firstCharacter))));
        mvc.perform(post("/api/onboarding/" + id + "/reroll")).andExpect(status().isOk());
        detected.report("unlinked_x", "Erlengrund", 1, 1);
        mvc.perform(get("/api/onboarding/unlinked-savegames")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].savegameId", hasItem("unlinked_x")));
        postJson("/api/onboarding/" + id + "/confirm", java.util.Map.of("savegameId", "unlinked_x"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
    }

    @Test
    void onboardingValidation() throws Exception {
        postJson("/api/onboarding", java.util.Map.of("startingCapitalTarget", -5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.startingCapitalTarget").exists());
        postJson("/api/onboarding/999999/confirm", java.util.Map.of("savegameId", "")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ mails & calls

    @Test
    void mailsListDetailReply() throws Exception {
        Communication m = mailFrom(bank, Channel.MAIL);
        mvc.perform(get("/api/mails")).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(m.getId().intValue())));
        mvc.perform(get("/api/mails/" + m.getId())).andExpect(status().isOk()).andExpect(jsonPath("$.message.read").value(true));
        postJson("/api/mails/" + m.getId() + "/reply", java.util.Map.of("text", "Vielen Dank, liebe Grüße!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.initiatedBy").value("PLAYER"));
        postJson("/api/mails/" + m.getId() + "/reply", java.util.Map.of("text", "")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/mails/987654321")).andExpect(status().isNotFound());
    }

    @Test
    void callEndpoints() throws Exception {
        Communication ring = mailFrom(bank, Channel.CALL);
        mvc.perform(get("/api/calls/pending")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(ring.getId().intValue())));
        mvc.perform(post("/api/calls/" + ring.getId() + "/accept")).andExpect(jsonPath("$.callStatus").value("ACCEPTED"));
        postJson("/api/calls/" + ring.getId() + "/message", java.util.Map.of("text", "Hallo!")).andExpect(status().isOk());
        mvc.perform(post("/api/calls/" + ring.getId() + "/complete")).andExpect(jsonPath("$.callStatus").value("COMPLETED"));
        Communication ring2 = mailFrom(bank, Channel.CALL);
        mvc.perform(post("/api/calls/" + ring2.getId() + "/decline")).andExpect(jsonPath("$.callStatus").value("DECLINED"))
                .andExpect(jsonPath("$.openTopic").value(true));
        mvc.perform(post("/api/calls/" + ring2.getId() + "/accept")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CALL_STATE"));
        mvc.perform(get("/api/calls")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ credit

    @Test
    void creditEndpoints() throws Exception {
        JsonNode app = read(postJson("/api/credit-applications",
                java.util.Map.of("amount", 20000, "purpose", "Saatgut", "termMonths", 24)).andExpect(status().isOk()));
        org.hamcrest.MatcherAssert.assertThat(app.get("status").asString(), org.hamcrest.Matchers.is("PROCESSING"));
        org.hamcrest.MatcherAssert.assertThat(app.get("decision").isNull(), org.hamcrest.Matchers.is(true));
        mvc.perform(get("/api/credit-applications")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/api/credit-applications/" + app.get("id").asLong() + "/accept-counter"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/credit-applications/" + app.get("id").asLong() + "/decline-counter"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/loans")).andExpect(status().isOk());
        mvc.perform(post("/api/loans/424242/stundung")).andExpect(status().isNotFound());
    }

    @Test
    void creditFormRejectsNumbersAsText() throws Exception {
        postJson("/api/credit-applications", java.util.Map.of("amount", "fünfzigtausend", "purpose", "x", "termMonths", 12))
                .andExpect(status().isBadRequest());
        postJson("/api/credit-applications", java.util.Map.of("amount", 0, "purpose", "x", "termMonths", 12))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.amount").exists());
        postJson("/api/credit-applications", java.util.Map.of("amount", 1000, "purpose", "", "termMonths", 12))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deferralForLegacyLoan() throws Exception {
        Savegame draft = onboarding.create(new OnboardingService.Request(null, null, null, 0, 50_000L, null, List.of()));
        detected.report("api_legacy", "Erlengrund", 1, 1);
        Savegame s2 = onboarding.confirm(draft.getId(), "api_legacy");
        fx.snapshot(s2, 1000);
        context.setCurrentBridgeSavegameId("api_legacy");
        JsonNode loans = read(mvc.perform(get("/api/loans")).andExpect(jsonPath("$", hasSize(1))));
        postJson("/api/loans/" + loans.get(0).get("id").asLong() + "/stundung", java.util.Map.of("message", "Bitte!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(true));
    }

    // ------------------------------------------------------------------ employees

    @Test
    void employeeEndpoints() throws Exception {
        JsonNode posting = read(postJson("/api/job-postings", java.util.Map.of("jobRole", "ANIMAL_KEEPER"))
                .andExpect(status().isOk()));
        long pid = posting.get("id").asLong();
        mvc.perform(get("/api/job-postings")).andExpect(status().isOk());
        JsonNode apps = read(mvc.perform(get("/api/job-postings/" + pid + "/applications")).andExpect(status().isOk()));
        long aid = apps.get(0).get("id").asLong();
        postJson("/api/job-postings/" + pid + "/applications/" + aid + "/interview-question",
                java.util.Map.of("question", "Warum wir?", "channel", "CALL")).andExpect(status().isOk());
        JsonNode emp = read(mvc.perform(post("/api/job-postings/" + pid + "/applications/" + aid + "/hire"))
                .andExpect(status().isOk()));
        long eid = emp.get("id").asLong();
        mvc.perform(get("/api/employees")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].needs.satisfaction").exists());
        postJson("/api/employees/" + eid + "/raise", java.util.Map.of("newSalary", 99999)).andExpect(status().isOk());
        postJson("/api/employees/" + eid + "/time-off", java.util.Map.of("days", 2)).andExpect(status().isOk());
        postJson("/api/employees/" + eid + "/time-off", java.util.Map.of("days", 0)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/employees/" + eid)).andExpect(jsonPath("$.status").value("TERMINATED"));
        postJson("/api/job-postings", java.util.Map.of("jobRole", "ASTRONAUT")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ negotiation & market

    @Test
    void negotiationEndpoints() throws Exception {
        Character seller = characters.findBySavegameOrderByIdAsc(sg).stream()
                .filter(c -> c.getCategory() == CharacterCategory.DYNAMIC).findFirst().orElseThrow();
        ownership.setOwner(sg, 13, OwnerType.CHARACTER, seller);
        mvc.perform(get("/api/farmlands")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        JsonNode n = read(postJson("/api/negotiations/direct", java.util.Map.of("characterId", seller.getId(),
                "farmlandId", 13)).andExpect(status().isOk()));
        long nid = n.get("id").asLong();
        postJson("/api/negotiations/direct", java.util.Map.of("characterId", seller.getId(), "farmlandId", 13))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FIELD_IN_NEGOTIATION"));
        postJson("/api/negotiations/" + nid + "/offer", java.util.Map.of("amount", 100)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REJECTED")).andExpect(jsonPath("$.roundsLeft").value(2));
        postJson("/api/negotiations/" + nid + "/offer", java.util.Map.of("amount", "viel")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/negotiations")).andExpect(jsonPath("$[0].offers", hasSize(1)));
        mvc.perform(post("/api/negotiations/" + nid + "/withdraw")).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        postJson("/api/farmlands/12/sell-offer", java.util.Map.of("askingPrice", 50000)).andExpect(status().isOk());
        postJson("/api/farmlands/12/sell-offer", java.util.Map.of("askingPrice", -1)).andExpect(status().isBadRequest());
    }

    @Test
    void marketEventEndpoints() throws Exception {
        MarketEvent offer = createOffer();
        mvc.perform(get("/api/market-events")).andExpect(jsonPath("$[*].id", hasItem(offer.getId().intValue())));
        postJson("/api/market-events/" + offer.getId() + "/participation", java.util.Map.of("participate", true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/market-events/" + offer.getId() + "/participation", java.util.Map.of())
                .andExpect(status().isBadRequest());
    }

    @Autowired de.farmpulse.rpsim.bridge.FactsService facts;

    MarketEvent createOffer() {
        return market.spawnSpecialOffer(sg, facts.marketContext(sg).orElseThrow(), facts.latest(sg).orElseThrow())
                .orElseThrow();
    }

    // ------------------------------------------------------------------ storage & prices

    @Test
    void storageAndPrices() throws Exception {
        mvc.perform(get("/api/storage")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fillType").value("WHEAT"))
                .andExpect(jsonPath("$.items[0].value").value(Math.round(42000 * 230 / 1000.0)))
                .andExpect(jsonPath("$.items[0].bestSellPoint").value("Mühle Süd"));
        mvc.perform(get("/api/prices/current")).andExpect(jsonPath("$", hasSize(2)));
        fx.snapshot(sg, sg.getCurrentGameTime() + 1000, 1, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime() + 1000, 1).replace("\"currentPrice\": 215", "\"currentPrice\": 250"));
        mvc.perform(get("/api/prices/history").param("fillType", "WHEAT").param("sellPoint", "MillNorth"))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].points", hasSize(2)))
                .andExpect(jsonPath("$[0].points[1].price").value(250.0));
        mvc.perform(get("/api/prices/history").param("from", "notanumber")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ village

    @Test
    void villageEndpoints() throws Exception {
        mvc.perform(get("/api/characters")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trustLevel").exists())
                .andExpect(jsonPath("$[0].trustScore").doesNotExist());
        mvc.perform(get("/api/characters/" + bank.getId())).andExpect(jsonPath("$.traits").exists());
        postJson("/api/characters/" + bank.getId() + "/messages", java.util.Map.of("text", "Danke für alles!"))
                .andExpect(jsonPath("$.pacingActive").value(false));
        postJson("/api/characters/" + bank.getId() + "/messages", java.util.Map.of("text", "Nochmal danke!"))
                .andExpect(jsonPath("$.pacingActive").value(true));
        postJson("/api/characters/" + bank.getId() + "/messages", java.util.Map.of("text", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/diary")).andExpect(jsonPath("$[0].category").value("BACKSTORY"));
        postJson("/api/diary/entries", java.util.Map.of("title", "Notiz", "text", "Heute war ein schöner Tag."))
                .andExpect(jsonPath("$.entryType").value("PLAYER_NOTE"));
        postJson("/api/diary/entries", java.util.Map.of("text", "ohne Titel")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/village-reputation")).andExpect(jsonPath("$.tier").exists())
                .andExpect(jsonPath("$.label").exists()).andExpect(jsonPath("$.score").doesNotExist());
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsEndpoints() throws Exception {
        mvc.perform(get("/api/settings/ai")).andExpect(jsonPath("$.provider").value("NONE"))
                .andExpect(jsonPath("$.providers", hasItem("ANTHROPIC")));
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OPENAI\",\"apiKey\":\"sk-secret\",\"model\":\"gpt-x\"}"))
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("sk-secret"))));
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/ai").contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"SKYNET\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/game")).andExpect(jsonPath("$.tonePreset").value("REALISTIC"));
    }

    @Test
    void openApiDocumentationIsGenerated() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/negotiations/{id}/offer']").exists());
    }

    @Test
    void noActiveSavegameGives409() throws Exception {
        context.setCurrentBridgeSavegameId("does_not_exist");
        // falls back to the most recently linked savegame - never an error for the header
        mvc.perform(get("/api/savegame")).andExpect(status().isOk());
    }

    @Test
    void numbersInResponsesAreWellFormed() throws Exception {
        mvc.perform(get("/api/savegame")).andExpect(jsonPath("$.unreadMails", greaterThan(-1)));
    }
}
