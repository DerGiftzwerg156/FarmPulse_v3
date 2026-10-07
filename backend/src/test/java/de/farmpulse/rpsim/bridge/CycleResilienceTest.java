package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NoticeRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Technical review 10/2026, Phase 1.1 (R-1): a failing listener must neither lose acknowledgements nor stop the game
 * logic, and a retried cycle must not run anything twice. Written before the fix - with the single-transaction cycle
 * of 1.7.0 every test here fails.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Import(CycleResilienceTest.Probe.class)
class CycleResilienceTest {

    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    /** Test listeners: a failing one between the real listeners and a counting one after all of them. */
    static class Probe {
        /** Failures left: 0 = works, n > 0 = fails n times, negative = always fails. */
        static final AtomicInteger dayFailures = new AtomicInteger();
        static final AtomicInteger ackFailures = new AtomicInteger();
        static final Map<Long, Integer> daysSeen = new ConcurrentHashMap<>();
        static final Map<String, Integer> acksSeen = new ConcurrentHashMap<>();

        private static void maybeFail(AtomicInteger failures, String what) {
            if (failures.get() != 0) {
                failures.decrementAndGet();
                throw new IllegalStateException("probe failure " + what);
            }
        }

        @EventListener
        @Order(55)
        @Transactional
        public void failingDay(GameDayPassedEvent e) {
            maybeFail(dayFailures, "day " + e.dayIndex());
        }

        @EventListener
        @Order(Integer.MAX_VALUE - 1)
        public void countingDay(GameDayPassedEvent e) {
            daysSeen.merge(e.dayIndex(), 1, Integer::sum);
        }

        @EventListener
        @Order(1)
        @Transactional
        public void failingAck(BridgeEvents.InstructionAcked e) {
            maybeFail(ackFailures, "ack " + e.instructionId());
            acksSeen.merge(e.instructionId(), 1, Integer::sum);
        }
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired OutboxService outboxService;
    @Autowired NoticeRepository notices;

    Savegame sg;
    String bridgeId;

    @BeforeEach
    void setUp() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(files.base());
        Probe.dayFailures.set(0);
        Probe.ackFailures.set(0);
        Probe.daysSeen.clear();
        Probe.acksSeen.clear();
        bridgeId = "sg_res_" + java.util.UUID.randomUUID(); // the in-memory database outlives the context
        sg = savegames.save(TestData.activeSavegame(bridgeId));
        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, 1000, 50_000)); // day 0
        cycle();
    }

    /** What the BridgeScheduler does: a failing cycle is logged and retried on the next poll. */
    private void cycle() {
        try {
            sync.runCycle();
        } catch (RuntimeException e) {
            // BridgeScheduler.poll logs and retries next cycle
        }
    }

    private void cycles(int n) {
        for (int i = 0; i < n; i++) {
            cycle();
        }
    }

    private Savegame reload() {
        return savegames.findById(sg.getId()).orElseThrow();
    }

    private long skippedNotices() {
        return notices.findBySavegameOrderByIdDesc(sg).stream().map(Notice::getKind)
                .filter(k -> k.name().equals("CYCLE_STEP_SKIPPED")).count();
    }

    private OutboxInstruction acknowledgedInstruction() {
        OutboxInstruction ins = outboxService.money(sg, -100, MoneyReason.OTHER, null, OutboxService.Related.none());
        TestBridge.write(files.ack(), """
            {"savegameId":"%s","acks":[{"instructionId":"%s","appliedAtGameTime":1500,"status":"APPLIED"}]}"""
                .formatted(bridgeId, ins.getInstructionId()));
        return ins;
    }

    @Test
    void aBrokenDayListenerIsSkippedAfterThreeAttemptsAndTheGameGoesOn() {
        Probe.dayFailures.set(-1);
        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, 2 * GameTime.MS_PER_DAY + 1000, 50_000));

        cycles(5);

        assertThat(reload().getLastProcessedGameDay()).isEqualTo(2);
        assertThat(reload().getCurrentGameTime()).isEqualTo(2 * GameTime.MS_PER_DAY + 1000);
        assertThat(Probe.daysSeen).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 1, 2L, 1)); // every day once
        assertThat(skippedNotices()).isPositive();
    }

    @Test
    void aTransientDayFailureIsRetriedWithoutRunningAnythingTwice() {
        Probe.dayFailures.set(1);
        TestBridge.write(files.farmFacts(), TestData.farmFacts(bridgeId, 2 * GameTime.MS_PER_DAY + 1000, 50_000));

        cycles(3);

        assertThat(reload().getLastProcessedGameDay()).isEqualTo(2);
        assertThat(Probe.daysSeen).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 1, 2L, 1));
        assertThat(skippedNotices()).isZero();
    }

    @Test
    void aBrokenAckListenerDoesNotLoseTheAcknowledgement() {
        Probe.ackFailures.set(-1);
        OutboxInstruction ins = acknowledgedInstruction();

        cycles(5);

        assertThat(outbox.findByInstructionId(ins.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.APPLIED);
        assertThat(skippedNotices()).isPositive();
    }

    @Test
    void aTransientAckFailureIsRetriedOnceAndApplied() {
        Probe.ackFailures.set(1);
        OutboxInstruction ins = acknowledgedInstruction();

        cycles(3);

        assertThat(outbox.findByInstructionId(ins.getInstructionId()).orElseThrow().getStatus())
                .isEqualTo(InstructionStatus.APPLIED);
        assertThat(Probe.acksSeen).containsExactlyEntriesOf(Map.of(ins.getInstructionId(), 1));
        assertThat(skippedNotices()).isZero();
    }
}
