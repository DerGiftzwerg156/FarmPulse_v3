package de.farmpulse.rpsim.narration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import de.farmpulse.rpsim.ai.AiSettingsService;
import de.farmpulse.rpsim.ai.FakeAiProvider;
import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Technical review 10/2026, Phase 1.5/1.6 (R-3): a slow AI provider holds neither the bridge cycle nor a database
 * transaction. Bridge polling and narration worker run for real here (both are switched off in the other tests).
 */
@SpringBootTest(properties = {"rpsim.bridge.enabled=true", "rpsim.bridge.poll-interval-ms=100",
        "rpsim.ai.worker-enabled=true", "rpsim.ai.worker-interval-ms=100"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerIsolationTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired AiSettingsService settings;
    @Autowired FakeAiProvider fake;
    @Autowired NarrationRequestService requests;
    @Autowired NarrationJobRepository jobs;
    @Autowired SavegameRepository savegames;
    @Autowired BridgeFiles files;
    @Autowired TransactionTemplate tx;
    @Autowired RpsimProperties props;

    final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        fake.reset();
        Files.deleteIfExists(Path.of(props.getAi().getLocalConfigFile()));
    }

    @Test
    void aBlockingAiCallHoldsNeitherTheBridgeCycleNorATransaction() throws Exception {
        String bridgeId = "sg_slow_ai_" + UUID.randomUUID();
        Savegame sg = savegames.save(TestData.activeSavegame(bridgeId));
        settings.save("FAKE", null, null, null);
        CountDownLatch entered = new CountDownLatch(1);
        List<Boolean> transactionDuringCall = new CopyOnWriteArrayList<>();
        fake.setOnCall(p -> {
            transactionDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS); // a provider that hangs like a slow Ollama
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Long jobId = tx.execute(s -> requests.request(savegames.findById(sg.getId()).orElseThrow(),
                NarrationEventType.REPLY).submit().getId());

        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the worker called the AI").isTrue();
        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, 2 * GameTime.MS_PER_DAY + 1000, 50_000));

        // the bridge keeps working while the AI call hangs
        assertThat(waitFor(() -> savegames.findById(sg.getId()).orElseThrow().getCurrentGameTime()
                == 2 * GameTime.MS_PER_DAY + 1000)).as("bridge cycle ran during the AI call").isTrue();
        assertThat(transactionDuringCall).as("database transaction open during the AI call").containsOnly(false);

        release.countDown();
        assertThat(waitFor(() -> jobs.findById(jobId).map(NarrationJob::getStatus).orElseThrow() == NarrationJobStatus.DONE))
                .as("job finished after the AI answered").isTrue();
    }

    static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
