package de.farmpulse.rpsim.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import de.farmpulse.rpsim.ai.AiSettingsService;
import de.farmpulse.rpsim.ai.FakeAiProvider;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Technical review 10/2026, Phase 1.6 (R-3): claim with lease, AI call outside the transaction, store. */
@SpringBootTest
@Import({Fixtures.class, NarrationLeaseTest.FailingSink.class})
class NarrationLeaseTest {

    /** A text target whose store step always fails. */
    static class FailingSink implements NarrationSink {
        @Override
        public String targetType() {
            return "TEST_FAILING";
        }

        @Override
        public void deliver(NarrationJob job, String subject, String body, boolean fallback) {
            throw new IllegalStateException("target gone");
        }
    }

    @Autowired Fixtures fx;
    @Autowired NarrationRequestService requests;
    @Autowired AiNarrationService narration;
    @Autowired NarrationJobRepository jobs;
    @Autowired CommunicationRepository communications;
    @Autowired AiSettingsService settings;
    @Autowired FakeAiProvider fake;
    @Autowired RpsimProperties props;
    @Autowired PlatformTransactionManager transactions;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
        settings.save("FAKE", null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        fake.reset();
        Files.deleteIfExists(Path.of(props.getAi().getLocalConfigFile()));
    }

    NarrationJob job() {
        return requests.request(sg, NarrationEventType.REPLY).from(bank).submit();
    }

    void change(Long jobId, Consumer<NarrationJob> change) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s -> change.accept(jobs.findById(jobId).orElseThrow()));
    }

    @Test
    void aJobWhoseLeaseRanOutIsClaimedAgain() {
        Long id = job().getId();
        change(id, j -> { // the backend stopped during the AI call
            j.setStatus(NarrationJobStatus.IN_PROGRESS);
            j.setLeaseUntil(Instant.now().minusSeconds(1));
        });
        assertThat(jobs.findClaimableIds(Instant.now())).contains(id);

        assertThat(narration.process(id)).isNotNull();

        NarrationJob done = jobs.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(NarrationJobStatus.DONE);
        assertThat(done.getLeaseUntil()).isNull();
        assertThat(done.getAttempts()).isEqualTo(1);
    }

    @Test
    void aRunningClaimIsNotTakenTwice() {
        Long id = job().getId();
        change(id, j -> {
            j.setStatus(NarrationJobStatus.IN_PROGRESS);
            j.setLeaseUntil(Instant.now().plusSeconds(60));
        });
        assertThat(jobs.findClaimableIds(Instant.now())).doesNotContain(id);
        assertThat(narration.process(id)).isNull();
        assertThat(fake.prompts()).isEmpty();
    }

    @Test
    void aJobBeforeItsGameTimeIsNotClaimed() {
        Long id = requests.request(sg, NarrationEventType.REPLY).from(bank).delay(1000).submit().getId();
        assertThat(jobs.findClaimableIds(Instant.now())).doesNotContain(id);
    }

    @Test
    void theResultOfAnOutdatedClaimIsDropped() {
        Long id = job().getId();
        // while the AI answers, the lease runs out and another run claims the job
        fake.setOnCall(p -> change(id, j -> j.setLeaseUntil(Instant.now().plusSeconds(60))));

        assertThat(narration.process(id)).isNull();

        assertThat(communications.findBySavegameOrderByIdAsc(sg)).isEmpty(); // no second mail
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(NarrationJobStatus.IN_PROGRESS);
    }

    @Test
    void aFailingStoreStepReleasesTheJob() {
        Long id = requests.request(sg, NarrationEventType.REPLY).from(bank).target("TEST_FAILING", 1L).submit().getId();

        assertThatThrownBy(() -> narration.process(id)).hasMessageContaining("target gone");

        NarrationJob j = jobs.findById(id).orElseThrow();
        assertThat(j.getStatus()).isEqualTo(NarrationJobStatus.PENDING); // the next worker run retries it
        assertThat(j.getLeaseUntil()).isNull();
        assertThat(j.getAttempts()).isEqualTo(1);
        assertThat(j.getLastError()).contains("target gone");
    }

    @Test
    void anUnexpectedProviderFailureReleasesTheJobAtOnce() {
        Long id = job().getId();
        fake.setOnCall(p -> {
            throw new IllegalArgumentException("provider bug");
        });

        assertThatThrownBy(() -> narration.process(id)).hasMessageContaining("provider bug");

        NarrationJob j = jobs.findById(id).orElseThrow();
        assertThat(j.getStatus()).isEqualTo(NarrationJobStatus.PENDING); // not blocked until the lease runs out
        assertThat(j.getLeaseUntil()).isNull();
        assertThat(j.getLastError()).contains("provider bug");
    }

    @Test
    void theLeaseCoversTheLongestAiStep() {
        // timeout 30 s x 2 attempts x 2 (the Anthropic SDK retries once) + 1 minute
        assertThat(narration.lease()).isEqualTo(Duration.ofSeconds(180));
    }
}
