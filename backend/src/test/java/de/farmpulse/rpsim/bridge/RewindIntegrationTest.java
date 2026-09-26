package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import de.farmpulse.rpsim.api.NoticeController;
import de.farmpulse.rpsim.api.Requests.NoticeActionRequest;
import de.farmpulse.rpsim.domain.BridgeRewind;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.RewindStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.notice.NoticeService;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** TODO.md T-02: savegame reloaded without saving - lost bookings are re-sent (automatically or on decision). */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RewindIntegrationTest {

    static Path dir = TestBridge.newDir();
    static final long DAY = GameTime.MS_PER_DAY;
    static final long HOUR = GameTime.MS_PER_HOUR;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired SavegameRepository savegames;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired OutboxService outboxService;
    @Autowired RewindService rewinds;
    @Autowired NoticeService notices;
    @Autowired NoticeController noticeApi;
    @Autowired FarmlandOwnershipService ownership;

    Savegame sg;
    String id;

    @BeforeEach
    void clean() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(files.base());
        id = "sg_rw_" + System.nanoTime();
        sg = savegames.save(TestData.activeSavegame(id));
    }

    private void facts(long gameTime) {
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, gameTime, 100_000));
        sync.runCycle();
    }

    private void ack(String... entries) {
        TestBridge.write(files.ack(), "{\"savegameId\":\"" + id + "\",\"acks\":[" + String.join(",", entries)
                + "],\"contractReports\":[]}");
        sync.runCycle();
    }

    private static String applied(OutboxInstruction o, long at) {
        return "{\"instructionId\":\"%s\",\"appliedAtGameTime\":%d,\"status\":\"APPLIED\"}".formatted(o.getInstructionId(), at);
    }

    private InstructionStatus status(OutboxInstruction o) {
        return outbox.findByInstructionId(o.getInstructionId()).orElseThrow().getStatus();
    }

    private BridgeRewind rewind() {
        List<BridgeRewind> all = rewinds.list(savegames.findById(sg.getId()).orElseThrow());
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    /** Old timeline: "saved" at day 10, one booking before and one after the save, played until day 10 + 5 h. */
    private OutboxInstruction[] playAndApply() {
        facts(10 * DAY - 2 * HOUR);
        OutboxInstruction before = outboxService.money(sg, 500, MoneyReason.SUBSIDY, "vor dem Speichern",
                OutboxService.Related.none());
        OutboxInstruction after = outboxService.money(sg, 25_000, MoneyReason.CREDIT_DISBURSEMENT, "Kredit",
                OutboxService.Related.none());
        sync.runCycle();
        ack(applied(before, 10 * DAY - HOUR), applied(after, 10 * DAY + 2 * HOUR));
        facts(10 * DAY + 5 * HOUR);
        assertThat(status(after)).isEqualTo(InstructionStatus.APPLIED);
        return new OutboxInstruction[] {before, after};
    }

    @Test
    void shortRewindResendsLostBookingsAutomatically() {
        OutboxInstruction[] ins = playAndApply();
        facts(10 * DAY); // reloaded the save of day 10 -> 5 h rewind
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.AWAITING_ACK);

        // the ack file on disk is still the one from before the reload -> not evaluated
        ack(applied(ins[0], 10 * DAY - HOUR), applied(ins[1], 10 * DAY + 2 * HOUR), "{\"instructionId\":\"x\","
                + "\"appliedAtGameTime\":1,\"status\":\"APPLIED\"}");
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.AWAITING_ACK);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.APPLIED);

        // rebuilt from the reloaded savegame: the disbursement is missing
        ack(applied(ins[0], 10 * DAY - HOUR));
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.RESENT);
        assertThat(status(ins[0])).isEqualTo(InstructionStatus.APPLIED);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.PENDING);
        assertThat(TestBridge.read(files.instructions())).contains(ins[1].getInstructionId())
                .doesNotContain(ins[0].getInstructionId());
        assertThat(notices.open(savegames.findById(sg.getId()).orElseThrow())).singleElement()
                .satisfies(n -> assertThat(n.getKind()).isEqualTo(NoticeKind.REWIND_RESENT));

        // the mod executes it again with the same instructionId
        ack(applied(ins[0], 10 * DAY - HOUR), applied(ins[1], 10 * DAY + HOUR));
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.APPLIED);
    }

    @Test
    void deepRewindAsksThePlayerAndResendsOnDecision() {
        OutboxInstruction[] ins = playAndApply();
        facts(12 * DAY); // continue playing for two more days
        facts(10 * DAY); // then load the save of day 10
        ack(applied(ins[0], 10 * DAY - HOUR));
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.AWAITING_PLAYER);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.APPLIED);
        var notice = noticeApi.open().getFirst();
        assertThat(notice.kind()).isEqualTo("REWIND_DECISION");
        assertThat(notice.details()).containsEntry("count", 1).containsEntry("moneyTotal", 25_000);

        noticeApi.resolve(notice.id(), new NoticeActionRequest(RewindService.RESEND));
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.RESENT);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.PENDING);
        assertThat(noticeApi.open()).isEmpty();
    }

    @Test
    void deepRewindKeepDecisionBooksNothing() {
        OutboxInstruction[] ins = playAndApply();
        facts(12 * DAY);
        facts(10 * DAY);
        ack(applied(ins[0], 10 * DAY - HOUR));
        noticeApi.resolve(noticeApi.open().getFirst().id(), new NoticeActionRequest(RewindService.KEEP));
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.KEPT);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.APPLIED);
    }

    @Test
    void nothingLostWhenEverythingWasSaved() {
        OutboxInstruction[] ins = playAndApply();
        facts(10 * DAY + 3 * HOUR); // saved after the disbursement
        ack(applied(ins[0], 10 * DAY - HOUR), applied(ins[1], 10 * DAY + 2 * HOUR));
        assertThat(rewind().getStatus()).isEqualTo(RewindStatus.NOTHING_LOST);
        assertThat(status(ins[1])).isEqualTo(InstructionStatus.APPLIED);
    }

    @Test
    void lostFarmlandPurchaseIsNotReconciledAwayWhileItIsResent() {
        TestBridge.write(files.marketContext(), TestData.marketContext(id));
        facts(10 * DAY - 2 * HOUR);
        List<OutboxInstruction> deal = outboxService.farmlandDeal(sg, 13, true, 47_500, "Kauf Feld 13",
                OutboxService.Related.none());
        ownership.setOwner(savegames.findById(sg.getId()).orElseThrow(), 13, OwnerType.PLAYER, null);
        sync.runCycle();
        ack(applied(deal.get(0), 10 * DAY + HOUR), applied(deal.get(1), 10 * DAY + HOUR));
        // the game now owns farmland 13 as well
        TestBridge.write(files.farmFacts(), TestData.farmFacts(id, 10 * DAY + 5 * HOUR, 100_000).replace(
                "[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]",
                "[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }, { \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000 }]"));
        sync.runCycle();

        facts(10 * DAY); // reload: field 13 is not owned in the game any more
        Savegame fresh = savegames.findById(sg.getId()).orElseThrow();
        assertThat(ownership.get(fresh, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        ack();
        assertThat(status(deal.get(0))).isEqualTo(InstructionStatus.PENDING);
        assertThat(status(deal.get(1))).isEqualTo(InstructionStatus.PENDING);
        facts(10 * DAY + HOUR);
        assertThat(ownership.get(fresh, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
    }
}
