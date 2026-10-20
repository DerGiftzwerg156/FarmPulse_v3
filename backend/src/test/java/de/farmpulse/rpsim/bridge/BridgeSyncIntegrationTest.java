package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@org.springframework.context.annotation.Import(de.farmpulse.rpsim.support.Fixtures.class)
class BridgeSyncIntegrationTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired FactsSnapshotRepository snapshots;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired OutboxService outboxService;
    @Autowired DetectedSavegameRegistry detected;
    @Autowired JsonMapper json;
    @Autowired FactsService facts;

    @BeforeEach
    void clean() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(files.base());
        detected.clear();
    }

    @Test
    void unlinkedSavegameIsRegisteredForOnboarding() {
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_new", 1000, 5000));
        TestBridge.write(files.marketContext(), TestData.marketContext("sg_new"));
        var res = sync.runCycle();
        assertThat(res.factsForUnlinkedSavegame()).isTrue();
        assertThat(detected.list()).extracting(DetectedSavegameRegistry.Detected::savegameId).contains("sg_new");
        assertThat(savegames.findByBridgeSavegameId("sg_new")).isEmpty();
        assertThat(snapshots.findAll()).noneMatch(s -> s.getRawJson().contains("\"sg_new\""));
    }

    @Test
    void linkedSavegameIsIngestedOnlyRawStates() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_linked"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_linked", 90_000_000, 245000));
        TestBridge.write(files.marketContext(), TestData.marketContext("sg_linked"));
        var res = sync.runCycle();
        assertThat(res.factsIngested()).isTrue();
        assertThat(res.marketContextRead()).isTrue();
        var snap = snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).orElseThrow();
        assertThat(snap.getBalance()).isEqualTo(245000);
        assertThat(snap.getRawJson()).contains("\"storage\"");
        assertThat(savegames.findById(sg.getId()).orElseThrow().getCurrentGameTime()).isEqualTo(90_000_000);
        // unchanged file is not ingested twice
        assertThat(sync.runCycle().factsIngested()).isFalse();
    }

    /** Logic review LR-L12: an instructions file that could not be written is written again in the next cycle. */
    @Test
    void aFailedWriteOfTheInstructionsIsRetried() throws Exception {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_write"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_write", 90_000_000, 245000));
        sync.runCycle();
        OutboxInstruction ins = outboxService.money(sg, 100, MoneyReason.SUBSIDY, "Zuschuss", OutboxService.Related.none());
        // the target is a non-empty directory: the move fails like a locked file
        Path xml = files.instructionsXml();
        Files.deleteIfExists(xml);
        Files.createDirectories(xml);
        Files.writeString(xml.resolve("lock"), "x");
        sync.runCycle();
        assertThat(Files.isDirectory(xml)).isTrue();
        org.springframework.util.FileSystemUtils.deleteRecursively(xml);
        sync.runCycle(); // nothing changed in the outbox - written again anyway
        assertThat(Files.readString(xml, StandardCharsets.UTF_8)).contains(ins.getInstructionId());
    }

    /** Logic review LR-L13: an ack without status is skipped, the other acks of the file still count. */
    @Test
    void anAckWithoutStatusDoesNotStopTheOthers() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_ack"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_ack", 90_000_000, 245000));
        sync.runCycle();
        OutboxInstruction ok = outboxService.money(sg, 100, MoneyReason.SUBSIDY, "Zuschuss", OutboxService.Related.none());
        OutboxInstruction broken = outboxService.money(sg, 200, MoneyReason.SUBSIDY, "Zuschuss", OutboxService.Related.none());
        sync.runCycle();
        TestBridge.write(files.ack(), "{\"savegameId\":\"sg_ack\",\"acks\":["
                + "{\"instructionId\":\"" + broken.getInstructionId() + "\",\"appliedAtGameTime\":90000001},"
                + "{\"instructionId\":\"" + ok.getInstructionId() + "\",\"appliedAtGameTime\":90000001,\"status\":\"APPLIED\"}"
                + "],\"contractReports\":[]}");
        sync.runCycle();
        assertThat(outbox.findByInstructionId(ok.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.APPLIED);
        assertThat(outbox.findByInstructionId(broken.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.PENDING);
    }

    @Test
    void storeCatalogIsStoredAtTheLinkedSavegameOnly() {
        // Roadmap vehicle dealer RFH-Q1: store_catalog.json is kept raw like the market context
        Savegame sg = savegames.save(TestData.activeSavegame("sg_cat"));
        String catalog = """
                { "savegameId": "sg_cat", "items": [{ "xmlFilename": "a.xml", "name": "A", "price": 1000,
                  "configurations": [], "defaultConfigurations": {}, "configurationSets": [] }] }""";
        TestBridge.write(files.storeCatalog(), catalog);
        sync.runCycle();
        Savegame stored = savegames.findById(sg.getId()).orElseThrow();
        assertThat(stored.getStoreCatalogJson()).isEqualTo(catalog);

        // a catalog of another (unlinked) savegame is left alone, an invalid one skipped
        TestBridge.write(files.storeCatalog(), catalog.replace("sg_cat", "sg_other"));
        sync.runCycle();
        TestBridge.write(files.storeCatalog(), "{ \"savegameId\": \"sg_cat\" }");
        sync.runCycle();
        assertThat(savegames.findById(sg.getId()).orElseThrow().getStoreCatalogJson()).isEqualTo(catalog);
    }

    @Test
    void storeCatalogWithBrokenEntriesOrNoUtf8IsStillStored() throws Exception {
        // the mod writes the file once per mission start: a broken entry or a byte that is no UTF-8 (mod text) must
        // not keep the dealer without a catalog until the next game start
        Savegame sg = savegames.save(TestData.activeSavegame("sg_cat2"));
        byte[] bytes = """
                { "savegameId": "sg_cat2", "items": [{ "xmlFilename": "a.xml", "name": "A#", "price": 1000,
                  "configurations": [{ "name": "frontloader", "options": [{ "index": 1, "name": "ohne",
                  "price": -2500 }] }], "defaultConfigurations": {}, "configurationSets": [] },
                  { "name": "kaputt", "price": 1 }] }""".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '#') bytes[i] = (byte) 0xE4; // Latin-1 "ä" in a UTF-8 file
        }
        Files.createDirectories(files.storeCatalog().getParent());
        Files.write(files.storeCatalog(), bytes);
        sync.runCycle();
        Savegame stored = savegames.findById(sg.getId()).orElseThrow();
        assertThat(stored.getStoreCatalogJson()).contains("a.xml");
        assertThat(facts.storeCatalog(stored).orElseThrow().items()).singleElement()
                .satisfies(i -> assertThat(i.name()).isEqualTo("A\uFFFD"));
    }

    @Test
    void truncatedOrInvalidFilesAreSkipped() {
        savegames.save(TestData.activeSavegame("sg_x"));
        TestBridge.write(files.farmFacts(), "{\"schemaVersion\":1,\"gameTime\":5,\"savegameId\":\"sg_x\",\"liqu");
        assertThat(sync.runCycle().factsIngested()).isFalse();
        TestBridge.write(files.farmFacts(), "{\"schemaVersion\":1,\"gameTime\":5,\"savegameId\":\"sg_x\"}");
        assertThat(sync.runCycle().factsIngested()).isFalse();
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_x", 5, 1));
        assertThat(sync.runCycle().factsIngested()).isTrue();
    }

    @Test
    void outboxIsWrittenWithEnvelopeAndAckUpdatesStatus() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_out"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_out", 1000, 5000));
        sync.runCycle();
        OutboxInstruction ins = outboxService.money(sg, -1800, MoneyReason.SALARY_PAYMENT, "Gehalt Klaus, Mai",
                OutboxService.Related.none());
        assertThat(sync.runCycle().instructionsWritten()).isEqualTo(1);
        JsonNode doc = json.readTree(TestBridge.read(files.instructions()));
        assertThat(doc.get("savegameId").asString()).isEqualTo("sg_out");
        JsonNode env = doc.get("instructions").get(0);
        assertThat(env.get("instructionId").asString()).isEqualTo(ins.getInstructionId());
        assertThat(env.get("type").asString()).isEqualTo("MONEY_TRANSACTION");
        assertThat(env.get("amount").asLong()).isEqualTo(-1800);
        assertThat(env.get("reason").asString()).isEqualTo("SALARY_PAYMENT");
        String xml = TestBridge.read(files.instructionsXml());
        String wrapped = xml.substring(xml.indexOf("<json>") + 6, xml.indexOf("</json>"));
        assertThat(json.readTree(wrapped)).isEqualTo(doc);

        TestBridge.write(files.ack(), """
            {"savegameId":"sg_out","acks":[{"instructionId":"%s","appliedAtGameTime":1500,"status":"APPLIED"}],
             "contractReports":[]}""".formatted(ins.getInstructionId()));
        var res = sync.runCycle();
        assertThat(res.acksApplied()).isEqualTo(1);
        assertThat(outbox.findByInstructionId(ins.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.APPLIED);
        assertThat(res.instructionsWritten()).isZero();
    }

    /** Roadmap V3 (R3-Q1): the optional result of an ack is kept with the instruction. */
    @Test
    void ackResultIsStoredWithTheInstruction() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_res"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_res", 1000, 5000));
        sync.runCycle();
        OutboxInstruction withResult = outboxService.money(sg, -1, MoneyReason.OTHER, null, OutboxService.Related.none());
        OutboxInstruction without = outboxService.money(sg, -2, MoneyReason.OTHER, null, OutboxService.Related.none());
        TestBridge.write(files.ack(), """
            {"savegameId":"sg_res","acks":[
              {"instructionId":"%s","appliedAtGameTime":1500,"status":"APPLIED","result":{"vehicleId":"veh_9"}},
              {"instructionId":"%s","appliedAtGameTime":1500,"status":"APPLIED"}]}"""
                .formatted(withResult.getInstructionId(), without.getInstructionId()));
        assertThat(sync.runCycle().acksApplied()).isEqualTo(2);
        OutboxInstruction stored = outbox.findByInstructionId(withResult.getInstructionId()).orElseThrow();
        assertThat(outboxService.ackResult(stored)).containsEntry("vehicleId", "veh_9");
        OutboxInstruction plain = outbox.findByInstructionId(without.getInstructionId()).orElseThrow();
        assertThat(plain.getAckResultJson()).isNull();
        assertThat(outboxService.ackResult(plain)).isEmpty();
    }

    @Test
    void ackOfForeignSavegameIsIgnored() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_a"));
        OutboxInstruction ins = outboxService.money(sg, 1, MoneyReason.OTHER, null, OutboxService.Related.none());
        TestBridge.write(files.ack(), """
            {"savegameId":"sg_unknown","acks":[{"instructionId":"%s","appliedAtGameTime":1,"status":"APPLIED"}]}"""
                .formatted(ins.getInstructionId()));
        sync.runCycle();
        assertThat(outbox.findByInstructionId(ins.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.PENDING);
    }

    @Test
    void farmlandDealIsOneBatchOfTwoInstructions() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_b"));
        var batch = outboxService.farmlandDeal(sg, 13, true, 47500, "Kauf Feld 13", OutboxService.Related.none());
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getBatchId()).isNotNull().isEqualTo(batch.get(1).getBatchId());
        assertThat(batch.get(1).getPayloadJson()).contains("FARMLAND_PURCHASE").contains("-47500");
    }

    @Autowired de.farmpulse.rpsim.support.Fixtures fx;
    @Autowired de.farmpulse.rpsim.communication.CommunicationService communications;
    @Autowired de.farmpulse.rpsim.prompt.PromptService prompts;
    @Autowired de.farmpulse.rpsim.repository.CommunicationRepository communicationRepo;

    /** Roadmap V2 R2-F1 / R2-F2: question out, answer back from player_responses.json, acknowledgement out. */
    @Test
    void anIncomingCallIsAcceptedInTheGame() {
        savegames.save(TestData.activeSavegame("sg_prompt"));
        TestBridge.write(files.farmFacts(), TestData.farmFacts("sg_prompt", 90_000_000, 5000));
        sync.runCycle();
        Savegame sg = savegames.findByBridgeSavegameId("sg_prompt").orElseThrow();
        var bank = fx.bank(sg);
        var call = communications.create(new de.farmpulse.rpsim.communication.CommunicationService.Draft(sg, bank,
                de.farmpulse.rpsim.domain.Channel.CALL, de.farmpulse.rpsim.domain.CommunicationInitiator.CHARACTER,
                "Ihr Kreditantrag", "Hallo?", de.farmpulse.rpsim.domain.CommunicationCategory.CREDIT, "REPLY", null, null,
                null, null, false, null));
        sync.runCycle();
        JsonNode prompt = null;
        for (JsonNode env : json.readTree(TestBridge.read(files.instructions())).get("instructions")) {
            if (env.get("type").asString().equals("PROMPT")) {
                prompt = env;
            }
        }
        assertThat(prompt).isNotNull();
        assertThat(prompt.get("title").asString()).isEqualTo("Anruf von Frau Berger");
        String promptId = prompt.get("promptId").asString();

        TestBridge.write(files.playerResponses(), """
            {"savegameId":"sg_prompt","responses":[{"responseId":"rsp_%s","promptId":"%s","answer":"YES",
             "gameTime":90000500}]}""".formatted(promptId, promptId));
        sync.runCycle();
        assertThat(prompts.processAnswers()).isEqualTo(1);
        assertThat(communicationRepo.findById(call.getId()).orElseThrow().getCallStatus())
                .isEqualTo(de.farmpulse.rpsim.domain.CallStatus.ACCEPTED);
        sync.runCycle();
        JsonNode doc = json.readTree(TestBridge.read(files.instructions()));
        assertThat(doc.get("ackedResponses").get(0).asString()).isEqualTo("rsp_" + promptId);
        assertThat(doc.get("withdrawnPrompts").get(0).asString()).isEqualTo(promptId);
        assertThat(doc.get("instructions").toString()).contains("im Browser bereit");
        // the mod keeps the answer until it sees the acknowledgement: reading it again changes nothing
        assertThat(prompts.processAnswers()).isZero();
    }

    /** DoD AP-3.3 / AP-2.2: backend reads files written by the real bridge simulator and vice versa. */
    @Test
    void roundTripWithRealBridgeSimulator() {
        assumeTrue(TestBridge.simulatorAvailable(), "node / bridge simulator not installed");
        String id = "sim_roundtrip";
        TestBridge.runSimulatorOnce(files.base(), "wohlhabender-hof", id, "--reset");
        Savegame sg = savegames.save(TestData.activeSavegame(id));
        sync.runCycle();
        long before = snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).orElseThrow().getBalance();

        OutboxInstruction ins = outboxService.money(sg, 12345, MoneyReason.SUBSIDY, "Förderung", OutboxService.Related.none());
        sync.runCycle();                                   // backend writes instructions.json
        TestBridge.runSimulatorOnce(files.base(), "wohlhabender-hof", id); // simulator applies + acks + exports
        sync.runCycle();                                   // backend reads ack + next snapshot

        assertThat(outbox.findByInstructionId(ins.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.APPLIED);
        long after = snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).orElseThrow().getBalance();
        assertThat(after).isEqualTo(before + 12345);
    }
}
