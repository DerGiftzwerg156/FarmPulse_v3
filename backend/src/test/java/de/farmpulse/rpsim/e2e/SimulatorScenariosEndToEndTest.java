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
    @Autowired de.farmpulse.rpsim.repository.GrowingFieldMonthRepository growing;
    @Autowired de.farmpulse.rpsim.time.GameTime gameTime;
    @Autowired de.farmpulse.rpsim.field.FieldBookService fieldBook;

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
        Savegame old = link("voller-silobestand", "sim_alt_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(old.getId()).orElseThrow()).orElseThrow();
            assertThat(f.finances()).isNull();
            assertThat(f.workforce()).isNull();
            assertThat(f.husbandries()).isNull();
            assertThat(f.fields()).isNull();
            assertThat(f.weather()).isNull();
        });
    }

    /** Roadmap V3 (R3-Q2): npcFields, tradeStorage and the shop vehicle catalog of nachbarhandel reach the backend. */
    @Test
    void roadmapV3BlocksArriveFromNachbarhandel() {
        Savegame trade = link("nachbarhandel", "sim_nachbar_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(trade.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.npcFields()).extracting(fd -> fd.farmlandId()).containsExactly(3, 5, 6, 8);
            assertThat(f.npcFields().get(2).fruitType()).isNull();
            assertThat(f.tradeStorage()).extracting(t -> t.fillType()).containsExactly("BARLEY", "STRAW", "WHEAT");
            assertThat(f.tradeStorage().get(1).freeCapacity()).isEqualTo(25_000.0);
            var ctx = facts.marketContext(sg).orElseThrow();
            assertThat(ctx.storeVehicles()).hasSize(5);
            assertThat(ctx.storeVehicles()).filteredOn(v -> v.motorized() == null).hasSize(1);
        });
        Savegame old = link("wohlhabender-hof", "sim_alt3_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(old.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.npcFields()).isNull();
            assertThat(f.tradeStorage()).isNull();
            assertThat(facts.marketContext(sg).orElseThrow().storeVehicles()).isNull();
        });
    }

    /**
     * Roadmap V3.1 (R31-Q2): snow height, categories, diesel, time of day, vehicle positions, spray types and field
     * outlines of the V3.1 scenarios reach the backend; an older scenario leaves them out.
     */
    @Test
    void roadmapV31FieldsArriveFromTheNewScenarios() {
        Savegame winter = link("winter-schnee", "sim_winter_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var f = facts.latest(savegames.findById(winter.getId()).orElseThrow()).orElseThrow();
            assertThat(f.weather().snowHeight()).isEqualTo(0.15);
            assertThat(f.calendar().dayTimeMs()).isNotNull();
            assertThat(f.vehiclePositions()).isEmpty();
            assertThat(f.assets().vehicles()).extracting(v -> v.category()).containsExactly("TRACTORSL", "TRACTORSM", "TRAILERS");
            assertThat(f.assets().vehicles().get(0).fuel().capacity()).isEqualTo(400.0);
            assertThat(f.assets().vehicles().get(2).fuel()).isNull();
        });
        Savegame contractor = link("lohnunternehmer", "sim_lohn_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(contractor.getId()).orElseThrow();
            assertThat(facts.latest(sg).orElseThrow().fields()).extracting(fd -> fd.sprayType())
                    .containsExactly("NONE", "MANURE", "LIQUID_MANURE", "NONE");
            var shapes = facts.marketContext(sg).orElseThrow().fieldShapes();
            assertThat(shapes.mapSize()).isEqualTo(2048.0);
            assertThat(shapes.fields()).hasSize(15);
        });
        Savegame old = link("wohlhabender-hof", "sim_alt31_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(old.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.vehiclePositions()).isNull();
            assertThat(f.calendar().dayTimeMs()).isNull();
            assertThat(f.weather().snowHeight()).isNull();
            assertThat(f.assets().vehicles()).allSatisfy(v -> assertThat(v.fuel()).isNull());
            assertThat(facts.marketContext(sg).orElseThrow().fieldShapes()).isNull();
        });
    }

    /** Roadmap V3.1 (R31-Q2): a VEHICLE_FUEL goes out, the simulator takes the diesel and the litres come back. */
    @Test
    void vehicleFuelReturnsTheLitresTakenInTheAck() {
        String id = "sim_fuel_" + System.nanoTime();
        Savegame sg = link("winter-schnee", id);
        String instructionId = tx.execute(s -> {
            OutboxInstruction o = new OutboxInstruction();
            o.setSavegame(savegames.findById(sg.getId()).orElseThrow());
            o.setInstructionId(OutboxService.newInstructionId());
            o.setType(de.farmpulse.rpsim.domain.InstructionType.VEHICLE_FUEL);
            o.setPayloadJson("{\"vehicleId\":\"veh_00002\",\"delta\":-500}");
            o.setStatus(InstructionStatus.PENDING);
            o.setCreatedAtGameTime(0);
            o.setCreatedAt(java.time.Instant.now());
            return outbox.save(o).getInstructionId();
        });
        sync.runCycle();
        TestBridge.runSimulatorOnce(dir, "winter-schnee", id);
        sync.runCycle();
        tx.executeWithoutResult(s -> {
            OutboxInstruction done = outbox.findByInstructionId(instructionId).orElseThrow();
            assertThat(done.getStatus()).isEqualTo(InstructionStatus.APPLIED);
            assertThat(outboxService.ackResult(done)).containsEntry("liters", 90);
        });
    }

    /**
     * Roadmap V3.2 (R32-Q2): the milk storage of investor-milch and the oil mill of grossauftrag reach the backend; an
     * older scenario exports its husbandries without the storage.
     */
    @Test
    void roadmapV32FieldsArriveFromTheNewScenarios() {
        Savegame dairy = link("investor-milch", "sim_milch_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            var h = facts.latest(savegames.findById(dairy.getId()).orElseThrow()).orElseThrow().husbandries().get(0);
            assertThat(h.storage()).singleElement().satisfies(m -> {
                assertThat(m.fillType()).isEqualTo("MILK");
                assertThat(m.amount()).isEqualTo(12000.0);
                assertThat(m.capacity()).isEqualTo(30000.0);
            });
        });
        Savegame bulk = link("grossauftrag", "sim_gross_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(bulk.getId()).orElseThrow();
            assertThat(facts.marketContext(sg).orElseThrow().sellPoints())
                    .anySatisfy(p -> assertThat(p.acceptedFillTypes()).contains("CANOLA"));
            assertThat(facts.latest(sg).orElseThrow().tradeStorage())
                    .anySatisfy(t -> assertThat(t.fillType()).isEqualTo("CANOLA"));
        });
        Savegame old = link("viehhandel", "sim_alt32_" + System.nanoTime());
        tx.executeWithoutResult(s -> assertThat(facts.latest(savegames.findById(old.getId()).orElseThrow()).orElseThrow()
                .husbandries()).allSatisfy(h -> assertThat(h.storage()).isNull()));
    }

    /**
     * Roadmap V3.3 (R33-Q2): the rolling / mulching levels, the harvest counter and the crops of the map of feldbuch
     * reach the backend; an older scenario leaves them out.
     */
    @Test
    void roadmapV33FieldsArriveFromTheFieldBookScenario() {
        Savegame book = link("feldbuch", "sim_feldbuch_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(book.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.fields()).extracting(fd -> fd.rollerLevel()).containsExactly(0, 0, 1);
            assertThat(f.fields()).extracting(fd -> fd.stubbleShredLevel()).containsExactly(0, 0, 0);
            assertThat(f.harvests()).singleElement().satisfies(h -> {
                assertThat(h.farmlandId()).isEqualTo(4);
                assertThat(h.fruitType()).isEqualTo("GRASS");
                assertThat(h.fillType()).isEqualTo("GRASS_WINDROW");
                assertThat(h.liters()).isEqualTo(18000.0);
            });
            var crops = facts.marketContext(sg).orElseThrow().fruitTypes();
            assertThat(crops).extracting(t -> t.name())
                    .containsExactly("BARLEY", "CANOLA", "GRASS", "MAIZE", "POTATO", "WHEAT");
            assertThat(crops).filteredOn(t -> t.name().equals("MAIZE")).singleElement()
                    .satisfies(t -> assertThat(t.products()).containsExactly("CHAFF"));
            // Roadmap V3.3 R33-F: the field book opened a running season per own field; the counted grass went to the
            // running grass season of field 4 (F3 rule 1)
            assertThat(fieldBook.all(sg)).extracting(de.farmpulse.rpsim.domain.FieldBookEntry::getFarmlandId)
                    .containsExactlyInAnyOrder(2, 4, 5);
            assertThat(fieldBook.all(sg)).filteredOn(x -> x.getFarmlandId() == 4).singleElement().satisfies(x -> {
                assertThat(x.fruitType()).isEqualTo("GRASS");
                assertThat(x.liters()).isEqualTo(18000.0);
                assertThat(x.isFirstEntry()).isTrue();
            });
        });
        Savegame old = link("lohnunternehmer", "sim_alt33_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(old.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.harvests()).isNull();
            assertThat(f.fields()).allSatisfy(fd -> assertThat(fd.rollerLevel()).isNull());
            assertThat(facts.marketContext(sg).orElseThrow().fruitTypes()).isNull();
        });
    }

    /**
     * Roadmap V4 (R4-Q3): the consumption counter, the feed stock, the food values of the husbandry, the purchase prices,
     * the food groups and the crop windows of hofplanung reach the backend; an older scenario leaves them out.
     */
    @Test
    void roadmapV4FieldsArriveFromThePlanningScenario() {
        Savegame plan = link("hofplanung", "sim_hofplanung_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(plan.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.inputs()).extracting(i -> i.fillType()).containsExactly("FERTILIZER", "SEEDS", "LIQUIDMANURE");
            assertThat(f.inputs().get(0).helperLiters()).isEqualTo(1800.0);
            assertThat(f.feedStock()).extracting(a -> a.fillType()).containsExactly("DRYGRASS_WINDROW", "SILAGE", "STRAW");
            var h = f.husbandries().get(0);
            assertThat(h.foodLitersPerHour()).isEqualTo(41.67);
            assertThat(h.trough()).extracting(a -> a.fillType()).containsExactly("DRYGRASS_WINDROW", "SILAGE");
            assertThat(h.feedConsumed()).extracting(c -> c.liters()).containsExactly(12000.0, 36000.0);
            var ctx = facts.marketContext(sg).orElseThrow();
            assertThat(ctx.fillTypeCosts()).anySatisfy(c -> {
                assertThat(c.fillType()).isEqualTo("SEEDS");
                assertThat(c.costPerLiter()).isEqualTo(0.9);
            });
            assertThat(ctx.animalFood()).singleElement().satisfies(a -> assertThat(a.groups()).extracting(g -> g.title())
                    .containsExactly("Futtermischung", "Silage", "Heu"));
            assertThat(ctx.fruitTypes()).filteredOn(t -> t.name().equals("WHEAT")).singleElement().satisfies(t -> {
                assertThat(t.plantablePeriods()).containsExactly(6, 7);
                assertThat(t.seedUsagePerSqm()).isEqualTo(0.1);
            });
        });
        Savegame old = link("feldbuch", "sim_alt4_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(old.getId()).orElseThrow();
            var f = facts.latest(sg).orElseThrow();
            assertThat(f.inputs()).isNull();
            assertThat(f.feedStock()).isNull();
            var ctx = facts.marketContext(sg).orElseThrow();
            assertThat(ctx.fillTypeCosts()).isNull();
            assertThat(ctx.animalFood()).isNull();
            assertThat(ctx.fruitTypes()).allSatisfy(t -> assertThat(t.plantablePeriods()).isNull());
        });
    }

    /** Roadmap V3.2 (R32-Q2): a HUSBANDRY_TRANSFER goes out, the simulator takes the milk out of the stable. */
    @Test
    void husbandryTransferTakesTheMilkOutOfTheStable() {
        String id = "sim_milk_" + System.nanoTime();
        Savegame sg = link("investor-milch", id);
        String instructionId = tx.execute(s -> {
            OutboxInstruction o = new OutboxInstruction();
            o.setSavegame(savegames.findById(sg.getId()).orElseThrow());
            o.setInstructionId(OutboxService.newInstructionId());
            o.setType(de.farmpulse.rpsim.domain.InstructionType.HUSBANDRY_TRANSFER);
            o.setPayloadJson("{\"husbandryUniqueId\":\"hus_00001\",\"fillType\":\"MILK\",\"amount\":5000}");
            o.setStatus(InstructionStatus.PENDING);
            o.setCreatedAtGameTime(0);
            o.setCreatedAt(java.time.Instant.now());
            return outbox.save(o).getInstructionId();
        });
        sync.runCycle();
        TestBridge.runSimulatorOnce(dir, "investor-milch", id);
        sync.runCycle();
        tx.executeWithoutResult(s -> {
            assertThat(outbox.findByInstructionId(instructionId).orElseThrow().getStatus())
                    .isEqualTo(InstructionStatus.APPLIED);
            var h = facts.latest(savegames.findById(sg.getId()).orElseThrow()).orElseThrow().husbandries().get(0);
            assertThat(h.storage().get(0).amount()).isEqualTo(7000.0);
        });
    }

    /** Roadmap V3 (R3-W2): the growing own fields of duerre-sommer are recorded for the drought aid. */
    @Test
    void duerreSommerRecordsTheGrowingOwnFields() {
        Savegame dry = link("duerre-sommer", "sim_duerre_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame sg = savegames.findById(dry.getId()).orElseThrow();
            assertThat(facts.latest(sg).orElseThrow().weather().raining()).isFalse();
            long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
            assertThat(growing.findBySavegameAndMonthIndexBetweenOrderByFarmlandIdAsc(sg, month, month))
                    .extracting(g -> g.getFarmlandId()).containsExactly(2, 4, 7);
        });
    }

    /** Roadmap V3 (R3-Q1 / R3-Q2): a VEHICLE_SPAWN goes out, the simulator delivers and the vehicleId comes back. */
    @Test
    void vehicleSpawnReturnsTheVehicleIdInTheAck() {
        String id = "sim_spawn_" + System.nanoTime();
        Savegame sg = link("nachbarhandel", id);
        String instructionId = tx.execute(s -> {
            OutboxInstruction o = new OutboxInstruction();
            o.setSavegame(savegames.findById(sg.getId()).orElseThrow());
            o.setInstructionId(OutboxService.newInstructionId());
            o.setType(de.farmpulse.rpsim.domain.InstructionType.VEHICLE_SPAWN);
            o.setPayloadJson("""
                    {"storeXmlFilename":"data/vehicles/deutzFahr/series5/series5.xml","ageMonths":36,
                     "operatingHours":2400,"damage":0.2,"wear":0.3,"price":52000,"moneyReason":"VEHICLE_PURCHASE"}""");
            o.setStatus(InstructionStatus.PENDING);
            o.setCreatedAtGameTime(0);
            o.setCreatedAt(java.time.Instant.now());
            return outbox.save(o).getInstructionId();
        });
        sync.runCycle();
        TestBridge.runSimulatorOnce(dir, "nachbarhandel", id);
        sync.runCycle();
        tx.executeWithoutResult(s -> {
            OutboxInstruction done = outbox.findByInstructionId(instructionId).orElseThrow();
            assertThat(done.getStatus()).isEqualTo(InstructionStatus.APPLIED);
            assertThat(outboxService.ackResult(done)).containsEntry("vehicleId", "veh_00003");
        });
    }

    /**
     * Roadmap vehicle dealer (RFH-Q5): store_catalog.json, the delivery points and the shop places of fahrzeughaendler
     * reach the backend; a VEHICLE_DELIVERY goes out and comes back with the ids of the new machines.
     */
    @Test
    void vehicleDealerContractArrivesAndADeliveryComesBack() {
        String id = "sim_dealer_" + System.nanoTime();
        Savegame sg = link("fahrzeughaendler", id);
        tx.executeWithoutResult(s -> {
            Savegame stored = savegames.findById(sg.getId()).orElseThrow();
            var catalog = facts.storeCatalog(stored).orElseThrow();
            assertThat(catalog.items()).extracting(i -> i.xmlFilename()).contains(
                    "data/vehicles/fendt/vario700/vario700.xml");
            assertThat(catalog.items()).filteredOn(i -> i.xmlFilename().contains("vario700")).singleElement()
                    .satisfies(i -> assertThat(i.configurations()).extracting(c -> c.name())
                            .containsExactly("baseColor", "motor", "wheel"));
            assertThat(facts.latest(stored).orElseThrow().deliveryPoints()).extracting(p -> p.seq()).containsExactly(1, 2);
            assertThat(facts.marketContext(stored).orElseThrow().storePlaces()).hasSize(2);
        });
        String instructionId = tx.execute(s -> {
            OutboxInstruction o = new OutboxInstruction();
            o.setSavegame(savegames.findById(sg.getId()).orElseThrow());
            o.setInstructionId(OutboxService.newInstructionId());
            o.setType(de.farmpulse.rpsim.domain.InstructionType.VEHICLE_DELIVERY);
            o.setPayloadJson("""
                    {"deliveryId":"dlv_1","items":[{"storeXmlFilename":"data/vehicles/fendt/vario700/vario700.xml",
                     "configurations":{"motor":{"index":2}}}],"place":{"x":-412.5,"z":233.75,"ry":1.571},
                     "fallbackToShop":true,"bookings":[{"amount":200000,"moneyReason":"VEHICLE_NEW_PURCHASE"},
                     {"amount":1100,"moneyReason":"VEHICLE_DELIVERY_FEE"}],"tradeIns":["veh_00001"]}""");
            o.setStatus(InstructionStatus.PENDING);
            o.setCreatedAtGameTime(0);
            o.setCreatedAt(java.time.Instant.now());
            return outbox.save(o).getInstructionId();
        });
        sync.runCycle();
        TestBridge.runSimulatorOnce(dir, "fahrzeughaendler", id);
        sync.runCycle();
        tx.executeWithoutResult(s -> {
            OutboxInstruction done = outbox.findByInstructionId(instructionId).orElseThrow();
            assertThat(done.getStatus()).isEqualTo(InstructionStatus.APPLIED);
            assertThat(outboxService.ackResult(done)).containsEntry("placeUsed", "DELIVERY_POINT")
                    .containsEntry("vehicleId1", "veh_00003").containsEntry("tradeInsRemoved", 1);
        });
        // an older scenario writes no catalog file and has no delivery blocks
        Savegame old = link("nachbarhandel", "sim_old_dealer_" + System.nanoTime());
        tx.executeWithoutResult(s -> {
            Savegame stored = savegames.findById(old.getId()).orElseThrow();
            assertThat(facts.latest(stored).orElseThrow().deliveryPoints()).isNull();
            assertThat(facts.marketContext(stored).orElseThrow().storePlaces()).isNull();
        });
    }

    @Test
    void konfliktModsAreShownInTheHeaderContext() {
        link("konflikt-mods", "sim_mods_" + System.nanoTime());
        assertThat(header.current().getBody().detectedMods()).containsExactly("FS25_MarketDynamics", "FS25_UsedPlus");
        assertThat(header.current().getBody().calendar()).isNotNull();
    }
}
