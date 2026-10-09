package de.farmpulse.rpsim.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NoticeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Technical review 10/2026, Phase 1.4 (R-2): a write based on an outdated read fails instead of silently overwriting
 * the other change - in REST as HTTP 409, in the bridge cycle as an immediate re-run of the listener.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Import({OptimisticLockingTest.ConflictingListener.class, OptimisticLockingTest.ConflictController.class})
class OptimisticLockingTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    /** A day listener that, on its first run, loses a race against another transaction on the same savegame. */
    static class ConflictingListener {
        static final AtomicInteger runs = new AtomicInteger();
        static volatile boolean raceOnce;

        @Autowired SavegameRepository savegames;
        @Autowired PlatformTransactionManager transactions;

        @EventListener
        @Transactional
        public void onDay(GameDayPassedEvent e) {
            runs.incrementAndGet();
            Savegame mine = savegames.findById(e.savegameId()).orElseThrow();
            if (raceOnce) {
                raceOnce = false;
                TransactionTemplate other = new TransactionTemplate(transactions);
                other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                other.executeWithoutResult(s -> savegames.findById(e.savegameId()).orElseThrow().setFarmName("Hof Konkurrenz"));
            }
            mine.setMapName("Riverbend"); // a real change: only dirty rows are version-checked
        }
    }

    /** Stands for any REST write whose commit hits a version conflict. */
    @RestController
    static class ConflictController {
        @PostMapping("/api/test/conflict")
        public void conflict() {
            throw new ObjectOptimisticLockingFailureException(Savegame.class, 1L);
        }
    }

    @Autowired SavegameRepository savegames;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired NoticeRepository notices;
    @Autowired MockMvc mvc;

    Savegame sg;
    String bridgeId;

    @BeforeEach
    void setUp() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(files.base());
        ConflictingListener.runs.set(0);
        ConflictingListener.raceOnce = false;
        bridgeId = "sg_lock_" + UUID.randomUUID();
        sg = savegames.save(TestData.activeSavegame(bridgeId));
    }

    @Test
    void aStaleWriteFailsAndTheOtherChangeSurvives() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Savegame stale = savegames.findById(sg.getId()).orElseThrow(); // read before the other change
        tx.executeWithoutResult(s -> savegames.findById(sg.getId()).orElseThrow().setFarmName("Hof Sonnenberg"));

        stale.setMapName("Riverbend");
        assertThatThrownBy(() -> savegames.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);

        Savegame now = savegames.findById(sg.getId()).orElseThrow();
        assertThat(now.getFarmName()).isEqualTo("Hof Sonnenberg"); // before 1.4 the stale save overwrote it with null
        assertThat(now.getMapName()).isEqualTo("Erlengrund");      // the stale change is not written either
    }

    @Test
    void aVersionConflictInRestIsHttp409() throws Exception {
        mvc.perform(post("/api/test/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inzwischen geändert")));
    }

    @Test
    void aCycleListenerThatLosesARaceRunsAgainWithoutCountingAsFailure() {
        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, 1000, 50_000));
        sync.runCycle();
        ConflictingListener.runs.set(0);
        ConflictingListener.raceOnce = true;

        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, GameTime.MS_PER_DAY + 1000, 50_000));
        sync.runCycle();

        assertThat(ConflictingListener.runs).hasValue(2); // lost the race once, ran again at once
        Savegame now = savegames.findById(sg.getId()).orElseThrow();
        assertThat(now.getLastProcessedGameDay()).isEqualTo(1);
        assertThat(now.getFarmName()).isEqualTo("Hof Konkurrenz"); // the other change is kept
        assertThat(now.getMapName()).isEqualTo("Riverbend");       // and so is the listener's
        assertThat(notices.findBySavegameOrderByIdDesc(now)).extracting(Notice::getKind).isEmpty();
    }
}
