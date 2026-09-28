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

    /** Roadmap V2 (R2-Q2): the optional blocks of the new scenarios reach the backend; older scenarios have none. */
    @Test
    void roadmapV2BlocksArriveOnlyFromScenariosThatExportThem() {
        Savegame helpers = link("helfer-hof", "sim_helfer_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(helpers.getId()).orElseThrow()).orElseThrow();
            assertThat(f.workforce().activeJobs()).extracting(j -> j.employeeId()).containsExactly(1L, null);
            assertThat(f.workforce().workedGameMs()).containsKeys("1", "2");
            assertThat(f.finances().periods()).isNotNull();
            assertThat(f.weather().raining()).isFalse();
            assertThat(f.husbandries()).isNull();
            assertThat(f.fields()).isNull();
        });
    }

    @Test
    void roadmapV2HusbandriesAndFieldsArrive() {
        Savegame stable = link("tierhof-krank", "sim_tierhof_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(stable.getId()).orElseThrow()).orElseThrow();
            assertThat(f.husbandries()).extracting(h -> h.husbandryUniqueId()).containsExactly("hus_00001", "hus_00002");
            assertThat(f.husbandries().get(0).conditions()).isNotEmpty();
            assertThat(f.husbandries().get(1).productivity()).isNull();
            assertThat(f.workforce()).isNull();
        });
        Savegame harvest = link("ernte-herbst", "sim_ernte_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(harvest.getId()).orElseThrow()).orElseThrow();
            assertThat(f.fields()).extracting(fd -> fd.farmlandId()).containsExactly(2, 4, 6, 7);
            assertThat(f.fields().get(3).fruitType()).isNull();
            assertThat(f.weather().raining()).isTrue();
            assertThat(f.calendar().period()).isEqualTo(7);
        });
        Savegame old = link("wohlhabender-hof", "sim_alt_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(old.getId()).orElseThrow()).orElseThrow();
            assertThat(f.finances()).isNull();
            assertThat(f.workforce()).isNull();
            assertThat(f.husbandries()).isNull();
            assertThat(f.fields()).isNull();
            assertThat(f.weather()).isNull();
        });
    }

    @Test
    void konfliktModsAreShownInTheHeaderContext() {
        link("konflikt-mods", "sim_mods_" + System.nanoTime());
        assertThat(header.current().getBody().detectedMods()).containsExactly("FS25_MarketDynamics", "FS25_UsedPlus");
        assertThat(header.current().getBody().calendar()).isNotNull();
    }
}
