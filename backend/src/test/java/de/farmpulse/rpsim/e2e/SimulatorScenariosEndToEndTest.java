package de.farmpulse.rpsim.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import de.farmpulse.rpsim.api.SavegameController;
import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.NoticeService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

/** TODO T-14: the new simulator scenarios against the real backend (skipped without node / simulator). */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SimulatorScenariosEndToEndTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired OutboxService outboxService;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NoticeService notices;
    @Autowired FactsService facts;
    @Autowired SavegameController header;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void requireSimulator() {
        assumeTrue(TestBridge.simulatorAvailable(), "node / bridge simulator not installed");
        FileSystemUtils.deleteRecursively(dir.toFile());
    }

    private Savegame link(String scenario, String id) {
        TestBridge.runSimulatorOnce(dir, scenario, id, "--reset");
        Savegame sg = savegames.save(TestData.activeSavegame(id));
        sync.runCycle();
        return sg;
    }

    @Test
    void knappeKasseRefusesTheDebitAndRaisesANotice() {
        String id = "sim_knapp_" + System.nanoTime();
        Savegame sg = link("knappe-kasse", id);
        OutboxInstruction debit = outboxService.money(sg, -2_500, MoneyReason.OTHER, "zu teuer", OutboxService.Related.none());
        sync.runCycle();
        TestBridge.runSimulatorOnce(dir, "knappe-kasse", id);
        sync.runCycle();
        assertThat(outbox.findByInstructionId(debit.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.FAILED);
        tx.executeWithoutResult(s -> assertThat(notices.open(savegames.findById(sg.getId()).orElseThrow()))
                .extracting(n -> n.getKind()).containsExactly(NoticeKind.INSTRUCTION_FAILED));
    }

    @Test
    void leasingHofExportsLeasedVehiclesAsLiabilities() {
        Savegame sg = link("leasing-hof", "sim_leasing_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(sg.getId()).orElseThrow()).orElseThrow();
            assertThat(f.assets().vehicles()).hasSize(1);
            assertThat(FactsService.leasedVehicleCount(f)).isEqualTo(2);
            assertThat(f.calendar()).isNotNull();
        });
    }

    @Test
    void konfliktModsAreShownInTheHeaderContext() {
        link("konflikt-mods", "sim_mods_" + System.nanoTime());
        assertThat(header.current().getBody().detectedMods()).containsExactly("FS25_MarketDynamics", "FS25_UsedPlus");
        assertThat(header.current().getBody().calendar()).isNotNull();
    }
}
