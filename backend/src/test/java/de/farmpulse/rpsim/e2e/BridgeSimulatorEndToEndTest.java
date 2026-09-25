package de.farmpulse.rpsim.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import de.farmpulse.rpsim.bridge.BridgeFiles;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageRelation;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.onboarding.OnboardingService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestBridge;
import de.farmpulse.rpsim.village.VillageRotationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

/**
 * AP-6.4: complete data flow Backend -> file bridge -> real bridge simulator (Node) -> file bridge -> Backend,
 * one scenario per core module. Skipped when node / the simulator dependencies are not installed.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BridgeSimulatorEndToEndTest {

    static final String ID = "map_erlengrund_e2e";
    static Path dir = TestBridge.newDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("rpsim.bridge.path", () -> dir.toString());
    }

    @Autowired BridgeSyncService sync;
    @Autowired BridgeFiles files;
    @Autowired OnboardingService onboarding;
    @Autowired SavegameRepository savegames;
    @Autowired FactsSnapshotRepository snapshots;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired EmployeeRepository employees;
    @Autowired CreditApplicationService credit;
    @Autowired MarketEventEngine market;
    @Autowired NegotiationEngine negotiation;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired HiringService hiring;
    @Autowired VillageRotationService rotation;
    @Autowired FactsService facts;
    @Autowired TransactionTemplate tx;

    Long savegameId;
    long started;

    @BeforeAll
    void requireSimulator() {
        assumeTrue(TestBridge.simulatorAvailable(), "node / bridge simulator not installed");
        FileSystemUtils.deleteRecursively(dir.toFile());
        started = System.nanoTime();
    }

    /** Runs one simulator cycle (optionally advancing game time) and one backend bridge cycle. */
    void simulate(String... extra) {
        TestBridge.runSimulatorOnce(dir, "wohlhabender-hof", ID, extra);
        sync.runCycle();
    }

    <T> T inTx(Function<Savegame, T> f) {
        return tx.execute(s -> f.apply(savegames.findById(savegameId).orElseThrow()));
    }

    List<OutboxInstruction> instructions(String reason) {
        return inTx(sg -> outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getPayloadJson().contains(reason)).toList());
    }

    long balance() {
        return inTx(sg -> snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).orElseThrow().getBalance());
    }

    @Test
    @Order(1)
    void onboardingAndStartingCapital() {
        TestBridge.runSimulatorOnce(dir, "wohlhabender-hof", ID, "--reset");
        sync.runCycle();
        assertThat(onboarding.unlinkedSavegames()).anyMatch(d -> d.savegameId().equals(ID));
        Savegame draft = onboarding.create(new OnboardingService.Request(FarmOrigin.RETURNED_HOME, VillageRelation.CONNECTED,
                null, 1_500_000, null, TonePreset.REALISTIC, List.of(JobRole.OFFICE_CLERK)));
        savegameId = onboarding.confirm(draft.getId(), ID).getId();
        sync.runCycle(); // first snapshot of the linked savegame -> STARTING_CAPITAL_ADJUSTMENT (2.4 M -> 1.5 M)
        assertThat(instructions("STARTING_CAPITAL_ADJUSTMENT")).singleElement()
                .satisfies(o -> assertThat(o.getPayloadJson()).contains("-900000"));
        simulate();
        assertThat(instructions("STARTING_CAPITAL_ADJUSTMENT").get(0).getStatus()).isEqualTo(InstructionStatus.APPLIED);
        assertThat(balance()).isEqualTo(1_500_000);
    }

    @Test
    @Order(2)
    void creditApplicationApprovalAndDisbursement() {
        Long appId = inTx(sg -> credit.submit(sg, 100_000, "Neue Halle", 60).getId());
        simulate("--advance-hours", "48");   // processing time passes, decision released, disbursement queued
        simulate();                           // simulator applies the disbursement
        inTx(sg -> {
            var a = credit.get(sg, appId);
            assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.ACCEPTED);
            return null;
        });
        assertThat(instructions("CREDIT_DISBURSEMENT")).singleElement()
                .satisfies(o -> assertThat(o.getStatus()).isEqualTo(InstructionStatus.APPLIED));
        List<String> types = inTx(sg -> jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList());
        assertThat(types).contains("CREDIT_APPROVED");
    }

    @Test
    @Order(3)
    void priceEventChangesOnlyItsSellPoint() {
        MarketEvent ev = inTx(sg -> market.spawnPriceEvent(sg, MarketEventType.DEMAND_SPIKE,
                facts.marketContext(sg).orElseThrow(), facts.latest(sg).orElseThrow(), sg.getCurrentGameTime(), true)
                .orElseThrow());
        double before = inTx(sg -> facts.latest(sg).orElseThrow().prices().stream()
                .filter(p -> p.sellPoint().equals(ev.getSellPoint()) && p.fillType().equals(ev.getFillType()))
                .findFirst().orElseThrow().currentPrice());
        sync.runCycle();
        simulate("--advance-hours", "40"); // beyond the ramp-up (max 36 h), still in hold
        double after = inTx(sg -> facts.latest(sg).orElseThrow().prices().stream()
                .filter(p -> p.sellPoint().equals(ev.getSellPoint()) && p.fillType().equals(ev.getFillType()))
                .findFirst().orElseThrow().currentPrice());
        assertThat(after / before).isBetween(ev.getPeakMultiplier() - 0.05, ev.getPeakMultiplier() + 0.05);
        assertThat(outboxStatus(ev.getInstructionId())).isEqualTo(InstructionStatus.APPLIED);
    }

    InstructionStatus outboxStatus(String instructionId) {
        return outbox.findByInstructionId(instructionId).orElseThrow().getStatus();
    }

    @Test
    @Order(4)
    void auctionAndDirectNegotiationTransferFarmland() {
        Negotiation auction = inTx(sg -> negotiation.startAuction(sg).orElseThrow());
        int auctionField = Integer.parseInt(auction.getAssetId());
        inTx(sg -> negotiation.placeOffer(sg, auction.getId(), Math.round(auction.getBasePrice() * 1.2)));
        sync.runCycle();
        simulate();
        simulate();
        inTx(sg -> {
            assertThat(ownership.get(sg, auctionField).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
            assertThat(facts.latest(sg).orElseThrow().assets().farmland())
                    .anyMatch(f -> f.farmlandId() == auctionField);
            assertThat(facts.marketContext(sg).orElseThrow().farmlands())
                    .anyMatch(f -> f.farmlandId() == auctionField && f.ownerFarmId() == 1);
            return null;
        });
        assertThat(instructions("FARMLAND_PURCHASE")).allMatch(o -> o.getStatus() == InstructionStatus.APPLIED);

        // direct negotiation with an NPC owner
        FarmlandOwnership target = inTx(sg -> {
            FarmlandOwnership o = ownership.list(sg).stream().filter(x -> x.getOwnerType() == OwnerType.UNCLAIMED)
                    // a daily auction roll may already have picked a field - never collide with it
                    .filter(x -> !negotiation.isBlocked(sg, AssetType.FARMLAND, String.valueOf(x.getFarmlandId())))
                    .findFirst().orElseThrow();
            var npc = sg.getId() == null ? null : de.farmpulse.rpsim.support.E2EHelper.firstDynamic(sg);
            ownership.setOwner(sg, o.getFarmlandId(), OwnerType.CHARACTER, npc);
            return o;
        });
        Negotiation direct = inTx(sg -> negotiation.startDirect(sg, ownership.get(sg, target.getFarmlandId()).orElseThrow()
                .getOwnerCharacter().getId(), target.getFarmlandId()));
        var outcome = inTx(sg -> negotiation.placeOffer(sg, direct.getId(), target.getReferencePrice()));
        assertThat(outcome.negotiation().getStatus()).isEqualTo(NegotiationStatus.ACCEPTED);
        sync.runCycle();
        simulate();
        simulate();
        inTx(sg -> {
            assertThat(facts.latest(sg).orElseThrow().assets().farmland())
                    .anyMatch(f -> f.farmlandId() == target.getFarmlandId());
            return null;
        });
    }

    @Test
    @Order(5)
    void hiringAndResignationEscalation() {
        Long employeeId = inTx(sg -> {
            var posting = hiring.createPosting(sg, JobRole.MACHINE_OPERATOR);
            Employee e = hiring.hire(sg, posting.getId(), hiring.applications(sg, posting.getId()).get(0).getId());
            e.setPayFairness(0);
            e.setWorkload(0);
            e.setAppreciation(0);
            return e.getId();
        });
        sync.runCycle();
        simulate("--advance-hours", String.valueOf(24 * 32)); // day-by-day catch-up over 32 game days
        simulate();
        inTx(sg -> {
            Employee e = employees.findById(employeeId).orElseThrow();
            assertThat(e.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
            assertThat(e.getCharacter().getTerminationReason()).isEqualTo(TerminationReason.RESIGNED);
            assertThat(jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList())
                    .contains("EMPLOYEE_WARNING", "EMPLOYEE_RESIGNATION");
            return null;
        });
        assertThat(instructions("SALARY_PAYMENT")).isNotEmpty()
                .allMatch(o -> o.getStatus() == InstructionStatus.APPLIED);
        assertThat(instructions("EMPLOYEE_EFFECT")).isNotEmpty();
    }

    @Test
    @Order(6)
    void villageRotation() {
        var changed = inTx(sg -> {
            sg.setDynamicRotationsThisYear(0);
            return rotation.rotate(sg).orElseThrow();
        });
        inTx(sg -> {
            assertThat(jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList())
                    .containsAnyOf("CHARACTER_FAREWELL", "CHARACTER_INTRODUCTION");
            return null;
        });
        assertThat(changed.getStatus()).isIn(CharacterStatus.ACTIVE, CharacterStatus.TERMINATED);
        System.out.printf("E2E bridge simulator scenarios took %.1f s%n", (System.nanoTime() - started) / 1e9);
    }
}
