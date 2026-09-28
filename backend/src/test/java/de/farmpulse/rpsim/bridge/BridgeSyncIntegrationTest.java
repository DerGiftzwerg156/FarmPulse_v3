package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
