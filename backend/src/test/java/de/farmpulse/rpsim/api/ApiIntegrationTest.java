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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /** Roadmap V2 R2-B4: farm bookkeeping from the booking journal; without journal "not available". */
    @Test
    void financesFromTheBookingJournal() throws Exception {
        mvc.perform(get("/api/finances")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.months", hasSize(0)));
        long t = sg.getCurrentGameTime() + 1000;
        fx.snapshot(sg, t, 1, TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 1, 1, 3, """
                [{ "year": 1, "period": 2, "byType": { "HARVEST_INCOME": 48200.4, "PURCHASE_FUEL": -3100,
                                                       "SHOP_PROPERTY_BUY": -90000, "MY_MOD_TYPE": 50 } },
                 { "year": 1, "period": 3, "byType": { "AI": -1250 } }]"""));
        mvc.perform(get("/api/finances")).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.months", hasSize(2)))
                .andExpect(jsonPath("$.months[0].complete").value(true))
                .andExpect(jsonPath("$.months[0].operatingIncome").value(48250))
                .andExpect(jsonPath("$.months[0].operatingExpenses").value(-3100))
                .andExpect(jsonPath("$.months[0].operatingResult").value(45150))
                .andExpect(jsonPath("$.months[0].investment").value(-90000))
                .andExpect(jsonPath("$.months[0].lines[0].category").value("HARVEST_INCOME"))
                .andExpect(jsonPath("$.months[0].lines[1].financeClass").value("OPERATING_INCOME"))
                .andExpect(jsonPath("$.months[1].complete").value(false));
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

    /** Roadmap V3 R3-T: milestones, farm name and the chronicle (download and print view). */
    @Test
    void milestonesFarmNameAndChronicle() throws Exception {
        mvc.perform(get("/api/milestones")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/settings/farm")).andExpect(jsonPath("$.farmName").value(nullValue()))
                .andExpect(jsonPath("$.mapName").value("Erlengrund"));
        mvc.perform(put("/api/settings/farm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("farmName", "x".repeat(61)))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/farm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("farmName", "  Hof Lindenhain "))))
                .andExpect(jsonPath("$.farmName").value("Hof Lindenhain"));
        mvc.perform(get("/api/diary/chronicle")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("chronik-Hof-Lindenhain.md")))
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("# Hofchronik – Hof Lindenhain")));
        mvc.perform(get("/api/diary/chronicle/view")).andExpect(jsonPath("$.farmName").value("Hof Lindenhain"))
                .andExpect(jsonPath("$.backstory").exists()).andExpect(jsonPath("$.reports", hasSize(0)));
    }

    // ------------------------------------------------------------------ roadmap V2 R2-E

    @Autowired de.farmpulse.rpsim.club.ClubService clubs;

    @Test
    void roleplayAreaEndpoints() throws Exception {
        // E1: tax overview without a booking journal, advisor offer as a contract
        mvc.perform(get("/api/tax")).andExpect(status().isOk())
                .andExpect(jsonPath("$.journalAvailable").value(false))
                .andExpect(jsonPath("$.ratePercent").value(25.0))
                .andExpect(jsonPath("$.lastAssessment").value(nullValue()))
                .andExpect(jsonPath("$.openBills").value(0));
        mvc.perform(post("/api/tax/advisor/offer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TAX_ADVISOR")).andExpect(jsonPath("$.status").value("OFFERED"));
        // E3: the family field is an own field (12), not a foreign one (13)
        mvc.perform(put("/api/farmlands/12/family-field")).andExpect(status().isOk());
        mvc.perform(get("/api/farmlands")).andExpect(jsonPath("$[?(@.farmlandId == 12)].familyField").value(true));
        mvc.perform(put("/api/farmlands/13/family-field")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OWN_FIELD"));
        mvc.perform(delete("/api/family-field")).andExpect(status().isOk());
        mvc.perform(get("/api/farmlands")).andExpect(jsonPath("$[?(@.farmlandId == 12)].familyField").value(false));
        // E4: sponsoring only with one of the offered tiers
        long id = clubs.requestSponsoring(sg, "FIRE_BRIGADE").getId();
        mvc.perform(get("/api/cases")).andExpect(jsonPath("$[?(@.id == %d)].tiers[1]".formatted(id)).value(500));
        postJson("/api/cases/" + id + "/sponsor", java.util.Map.of("amount", 300)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TIER"));
        postJson("/api/cases/" + id + "/sponsor", java.util.Map.of("amount", 500)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED")).andExpect(jsonPath("$.payoutAmount").value(500));
    }

    // ------------------------------------------------------------------ Hof-Tablet

    @Test
    void tasksCollectOpenDecisionsOfEveryArea() throws Exception {
        long sponsoring = clubs.requestSponsoring(sg, "FIRE_BRIGADE").getId();
        MarketEvent offer = createOffer();
        Communication ring = mailFrom(bank, Channel.CALL);
        mvc.perform(post("/api/tax/advisor/offer")).andExpect(status().isOk());
        JsonNode tasks = read(mvc.perform(get("/api/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingPrompts").value(0)));
        List<String> keys = new java.util.ArrayList<>();
        tasks.get("items").forEach(t -> keys.add(t.get("key").asString()));
        org.hamcrest.MatcherAssert.assertThat(keys, org.hamcrest.Matchers.hasItems("case-" + sponsoring,
                "market-" + offer.getId(), "call-" + ring.getId()));
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$.items[?(@.key == 'case-%d')].serviceCase.kind".formatted(sponsoring))
                        .value("SPONSORING_REQUEST"))
                .andExpect(jsonPath("$.items[?(@.type == 'CONTRACT_OFFER')].contract.kind").value("TAX_ADVISOR"))
                .andExpect(jsonPath("$.items[?(@.key == 'call-%d')].call.callStatus".formatted(ring.getId())).value("RINGING"));
        // sorted by deadline: the advisor offer has none and comes after the sponsoring request (7 days)
        org.hamcrest.MatcherAssert.assertThat(keys.indexOf("case-" + sponsoring),
                org.hamcrest.Matchers.lessThan(keys.indexOf(keys.stream().filter(k -> k.startsWith("contract-")).findFirst().orElseThrow())));
        // decided in its area -> gone from the list
        postJson("/api/cases/" + sponsoring + "/sponsor", java.util.Map.of("amount", 500)).andExpect(status().isOk());
        mvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$.items[?(@.key == 'case-%d')]".formatted(sponsoring), hasSize(0)));
    }

    @Test
    void calendarShowsMonthStartDebitsAndFixedDates() throws Exception {
        mvc.perform(get("/api/calendar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextMonthStart", greaterThan(0)))
                .andExpect(jsonPath("$.monthStartDebits[?(@.kind == 'SALARIES')].count").value(1))
                .andExpect(jsonPath("$.monthStartTotal", greaterThan(0)))
                .andExpect(jsonPath("$.agenda[0].kind").value("MONTH_START"))
                .andExpect(jsonPath("$.yearEvents", hasSize(0)));
        // with the FS25 calendar: festivals, tax assessment and rotation check per period
        long t = sg.getCurrentGameTime() + 1000;
        fx.snapshot(sg, t, 1, TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 1, 1, 2, "[]"));
        mvc.perform(get("/api/calendar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(1))
                .andExpect(jsonPath("$.yearEvents[?(@.kind == 'FESTIVAL' && @.reference == 'SCHUETZENFEST')].period").value(4))
                .andExpect(jsonPath("$.yearEvents[?(@.kind == 'TAX_ASSESSMENT')].period").value(1))
                .andExpect(jsonPath("$.yearEvents[?(@.kind == 'TAX_PREPAYMENT')]", hasSize(0)));
    }

    @Test
    void stablesAndWeatherFromTheLastFarmFacts() throws Exception {
        // older mod: animals only, no husbandry values and no weather
        mvc.perform(get("/api/stables")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tracked").value(false))
                .andExpect(jsonPath("$.animals").value(24))
                .andExpect(jsonPath("$.barns[0].type").value("COW"))
                .andExpect(jsonPath("$.barns[0].health").value(nullValue()))
                .andExpect(jsonPath("$.vetDue[0].type").value("COW"));
        mvc.perform(get("/api/savegame")).andExpect(jsonPath("$.weather").value(nullValue()));
        long t = sg.getCurrentGameTime() + 1000;
        fx.snapshot(sg, t, 1, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 1), """
                "husbandries": [{ "husbandryUniqueId": "hus_00003", "health": 38, "productivity": 0.61, "food": 0.12,
                                  "conditions": [{ "title": "Wasser", "ratio": 0.86 }, { "title": "Stroh", "ratio": 0.4 }] }],
                "weather": { "raining": true, "rainFallScale": 0.6, "groundWetness": 0.7, "temperature": 14.5 }"""));
        mvc.perform(get("/api/stables")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tracked").value(true))
                .andExpect(jsonPath("$.barns[0].health").value(38.0))
                .andExpect(jsonPath("$.barns[0].productivity").value(61.0))
                .andExpect(jsonPath("$.barns[0].food").value(0.12))
                .andExpect(jsonPath("$.barns[0].water").value(0.86))
                .andExpect(jsonPath("$.barns[0].conditions", hasSize(2)))
                .andExpect(jsonPath("$.healthWarnBelow").value(40.0))
                .andExpect(jsonPath("$.keepers").value(0));
        mvc.perform(get("/api/savegame")).andExpect(jsonPath("$.weather.raining").value(true))
                .andExpect(jsonPath("$.weather.temperature").value(14.5))
                .andExpect(jsonPath("$.weather.groundWetness").value(0.7));
    }

    @Test
    void onboardingWithFamily() throws Exception {
        JsonNode created = read(postJson("/api/onboarding", java.util.Map.of("startingCapitalTarget", 100000,
                "farmOrigin", "INHERITED", "familyParents", true, "familyPartner", false, "familyChildren", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cast[?(@.role == 'FAMILY')]", hasSize(2))));
        JsonNode parent = null;
        for (JsonNode c : created.get("cast")) {
            if (c.get("role").asString().equals("FAMILY")) {
                parent = c;
            }
        }
        String familyName = parent.get("name").asString().substring(parent.get("name").asString().lastIndexOf(' '));
        // a rerolled parent stays a parent of the family
        JsonNode rerolled = read(postJson("/api/onboarding/" + created.get("id").asLong() + "/reroll",
                java.util.Map.of("characterId", parent.get("characterId").asLong())).andExpect(status().isOk()));
        int parents = 0;
        for (JsonNode c : rerolled.get("cast")) {
            if (c.get("role").asString().equals("FAMILY")) {
                parents++;
                org.assertj.core.api.Assertions.assertThat(c.get("name").asString()).endsWith(familyName);
            }
        }
        org.assertj.core.api.Assertions.assertThat(parents).isEqualTo(2);
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
        // Roadmap V2 R2-A1 / R2-A3
        mvc.perform(get("/api/settings/helpers")).andExpect(jsonPath("$.helperWageMode").value("EMPLOYEES"))
                .andExpect(jsonPath("$.strictHelperLimit").value(false));
        mvc.perform(put("/api/settings/helpers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"helperWageMode\":\"VANILLA\",\"strictHelperLimit\":true}"))
                .andExpect(jsonPath("$.helperWageMode").value("VANILLA"))
                .andExpect(jsonPath("$.strictHelperLimit").value(true));
        mvc.perform(put("/api/settings/helpers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"helperWageMode\":\"FREE\",\"strictHelperLimit\":true}"))
                .andExpect(status().isBadRequest());
        // Roadmap V2 R2-C6
        mvc.perform(get("/api/settings/fields")).andExpect(jsonPath("$.fieldHintsEnabled").value(true))
                .andExpect(jsonPath("$.fieldsTracked").value(false));
        mvc.perform(put("/api/settings/fields").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldHintsEnabled\":false}"))
                .andExpect(jsonPath("$.fieldHintsEnabled").value(false));
        // Roadmap V2 R2-D
        mvc.perform(get("/api/settings/vanilla-bypass")).andExpect(jsonPath("$.reactionsEnabled").value(true))
                .andExpect(jsonPath("$.interestSurchargePercent").value(0.0));
        mvc.perform(put("/api/settings/vanilla-bypass").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reactionsEnabled\":false}"))
                .andExpect(jsonPath("$.reactionsEnabled").value(false));
        // Roadmap V2 R2-F2: occasions asked in the game, default only calls
        mvc.perform(get("/api/settings/prompts")).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.kinds", hasSize(1))).andExpect(jsonPath("$.kinds[0]").value("CALL"))
                .andExpect(jsonPath("$.allKinds", hasItem("TAX_BILL")));
        mvc.perform(put("/api/settings/prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kinds\":[\"CALL\",\"INVITATION\"]}"))
                .andExpect(jsonPath("$.kinds", hasSize(2)));
        mvc.perform(put("/api/settings/prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kinds\":[]}"))
                .andExpect(jsonPath("$.kinds", hasSize(0)));
        mvc.perform(put("/api/settings/prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kinds\":[\"SKYNET\"]}"))
                .andExpect(status().isBadRequest());
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
