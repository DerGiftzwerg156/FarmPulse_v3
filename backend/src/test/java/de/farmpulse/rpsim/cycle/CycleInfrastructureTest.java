package de.farmpulse.rpsim.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.CycleEvent;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameClockService.TickWork;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Review 10/2026 Phase 1.3: the journal keys and the queued payloads the cycle relies on. */
@SpringBootTest
class CycleInfrastructureTest {

    @Autowired ListenerAwareMulticaster multicaster;
    @Autowired ApplicationContext context;
    @Autowired CycleQueue queue;
    @Autowired SavegameRepository savegames;
    @Autowired PlatformTransactionManager transactions;

    static final GameTime.Anchor ANCHOR = new GameTime.Anchor(3, 6 * GameTime.MS_PER_DAY, 3, 5);

    /** One instance of every payload the bridge cycle delivers. */
    static List<Record> payloads() {
        return List.of(
                new GameTimeAdvancedEvent(1L, 0, GameTime.MS_PER_DAY),
                new GameDayPassedEvent(1L, 1, GameTime.MS_PER_DAY),
                new GameMonthPassedEvent(1L, 2, GameTime.MS_PER_DAY),
                new BridgeEvents.FactsIngested(1L, 7L, 1000, false),
                new BridgeEvents.MarketContextUpdated(1L),
                new BridgeEvents.InstructionAcked(1L, "ins_1", "APPLIED", "LOAN", 4L, Map.of("vehicleId", "veh_9", "n", 3)),
                new BridgeEvents.PlayerResponsesRead(1L, List.of(new BridgeDtos.PlayerAnswer("r1", "p1", "YES", 1000L))),
                new BridgeEvents.InstructionsWriting(1L),
                new BridgeEvents.Rewound(1L, 2000, 1000),
                new BridgeEvents.ContractReported(1L, "ins_2", 40, 100, "COMPLETED"),
                new CalendarChangedEvent(1L, ANCHOR, new GameTime.Anchor(3, 6 * GameTime.MS_PER_DAY, 5, 5)),
                new TickWork(1000, 3 * GameTime.MS_PER_DAY, 2 * GameTime.MS_PER_DAY));
    }

    @Test
    void everyListenerOfACycleEventHasItsOwnJournalKey() {
        for (Record payload : payloads()) {
            List<String> ids = multicaster.listenersFor(new PayloadApplicationEvent<>(context, payload)).stream()
                    .map(CycleDispatcher::listenerId).toList();
            assertThat(ids).as("listeners of %s", payload.getClass().getSimpleName()).doesNotHaveDuplicates()
                    .allSatisfy(id -> assertThat(id).contains("("));
        }
        // the day event reaches every daily listener of the game logic
        List<ApplicationListener<?>> day = List.copyOf(multicaster.listenersFor(
                new PayloadApplicationEvent<>(context, new GameDayPassedEvent(1L, 1, 0))));
        assertThat(day).hasSizeGreaterThanOrEqualTo(45);
    }

    @Test
    void everyQueuedPayloadSurvivesTheDatabase() {
        Savegame sg = savegames.save(TestData.activeSavegame("sg_cycle_" + java.util.UUID.randomUUID()));
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (Record payload : payloads()) {
            CycleEvent e = tx.execute(s -> queue.enqueue(savegames.findById(sg.getId()).orElseThrow(), payload));
            assertThat(queue.payload(queue.next(sg.getId()).orElseThrow())).isEqualTo(payload);
            queue.complete(e.getId());
        }
        assertThat(queue.hasWork(sg.getId())).isFalse();
    }

    @Test
    void shortListenerNames() {
        assertThat(CycleDispatcher.shortName(
                "de.farmpulse.rpsim.newspaper.VillageNewspaperService.onDay(de.farmpulse.rpsim.time.GameDayPassedEvent)"))
                .isEqualTo("VillageNewspaperService.onDay");
        assertThat(CycleDispatcher.shortName("de.x.Outer$Probe.failingDay(de.x.Event)")).isEqualTo("Probe.failingDay");
        assertThat(CycleDispatcher.shortName("de.x.SomeListener")).isEqualTo("x.SomeListener");
    }

    @Test
    void onlyOwnRecordsAreTurnedBackIntoObjects() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CycleQueue.type("java.lang.ProcessBuilder"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CycleQueue.type("de.farmpulse.rpsim.cycle.CycleQueue"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no record");
    }
}
