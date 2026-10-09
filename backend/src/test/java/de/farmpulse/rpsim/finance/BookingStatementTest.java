package de.farmpulse.rpsim.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.api.FinanceController;
import de.farmpulse.rpsim.api.Views.StatementEntryView;
import de.farmpulse.rpsim.api.Views.StatementView;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Booking statement ("Kontoauszug", owner decisions 2026-10-06): farm_facts.bookings stored and shown per month. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class BookingStatementTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired BookingStatementService statement;
    @Autowired BookingEntryRepository entries;
    @Autowired FinanceController controller;
    @Autowired SavegameContext context;

    Savegame sg;
    long t;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        context.setCurrentBridgeSavegameId(null);
    }

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t = sg.getCurrentGameTime();
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
    }

    /** One export at game time t: the bookings block and the own vehicles {@code id:name}. */
    private void export(long nextSeq, String bookings, String... vehicles) {
        String doc = TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 500_000, 2, 8,
                "[{ \"year\": 2, \"period\": 8, \"byType\": { \"AI\": -10 } }]");
        StringBuilder v = new StringBuilder("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }");
        for (String x : vehicles) {
            String[] p = x.split(":");
            v.append(", { \"uniqueId\": \"").append(p[0]).append("\", \"value\": 1000, \"condition\": 100, \"name\": \"")
                    .append(p[1]).append("\" }");
        }
        doc = doc.replace("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }", v.toString());
        int end = doc.lastIndexOf('}');
        doc = doc.substring(0, end) + ", \"bookings\": { \"nextSeq\": " + nextSeq + ", \"entries\": [" + bookings + "] } }";
        FactsSnapshot s = fx.snapshot(sg, t, 500_000, doc);
        statement.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        t += DAY / 24;
    }

    private static String line(long seq, String category, long amount, String extra) {
        return "{ \"seq\": " + seq + ", \"gameTime\": 1000, \"year\": 2, \"period\": 8, \"day\": 1, \"category\": \""
                + category + "\", \"amount\": " + amount + ", \"count\": 1" + (extra == null ? "" : ", " + extra) + " }";
    }

    private BookingEntry entry(long seq) {
        return entries.findBySavegameAndSeqBetween(sg, seq, seq).getFirst();
    }

    @Test
    void entriesAreStoredUpdatedInPlaceAndRemovedAfterAReloadWithoutSaving() {
        export(3, line(1, "AI", -10, null) + "," + line(2, "SOLD_PRODUCTS", 200,
                "\"fillType\": \"WHEAT\", \"sellPoint\": \"MillNorth\", \"liters\": 900"));
        // the daily sum grows, a tool booking with its note is added
        export(4, line(1, "AI", -25, "\"count\": 2") + "," + line(2, "SOLD_PRODUCTS", 300,
                "\"fillType\": \"WHEAT\", \"sellPoint\": \"MillNorth\", \"liters\": 1400") + ","
                + line(3, "RPSIM_SALARY_PAYMENT", -2400, "\"single\": true, \"note\": \"Gehalt Anna\""));
        assertThat(entries.findBySavegameAndSeqGreaterThanEqual(sg, 0)).hasSize(3); // only this savegame (E2E tests leave others)
        assertThat(entry(1).getAmount()).isEqualTo(-25);
        assertThat(entry(2).getLiters()).isEqualTo(1400);
        assertThat(entry(3).getNote()).isEqualTo("Gehalt Anna");
        assertThat(entry(3).isSingle()).isTrue();
        // reload without saving: the game is back at nextSeq 3, the salary no longer exists there
        export(3, line(1, "AI", -25, "\"count\": 2") + "," + line(2, "SOLD_PRODUCTS", 300,
                "\"fillType\": \"WHEAT\", \"sellPoint\": \"MillNorth\", \"liters\": 1400"));
        assertThat(entries.findBySavegameAndSeqGreaterThanEqual(sg, 3)).isEmpty();
        assertThat(entries.findBySavegameAndSeqBetween(sg, 1, 2)).hasSize(2);
    }

    @Test
    void aShopPurchaseGetsTheNameOfTheVehicleThatAppeared() {
        export(1, "");
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true")); // vehicle not loaded yet
        assertThat(entry(1).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_PENDING);
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true"), "veh_00050:Fendt 942 Vario");
        assertThat(entry(1).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_MATCHED);
        assertThat(entry(1).getVehicleNames()).isEqualTo("Fendt 942 Vario");
        // the sale: the vehicle disappears together with SHOP_VEHICLE_SELL
        export(3, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true") + ","
                + line(2, "SHOP_VEHICLE_SELL", 60000, "\"single\": true"));
        assertThat(entry(2).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_MATCHED);
        assertThat(entry(2).getVehicleNames()).isEqualTo("Fendt 942 Vario");
    }

    @Test
    void severalWaitingPurchasesAreNotAssigned() {
        export(1, "");
        export(3, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true") + ","
                + line(2, "SHOP_VEHICLE_BUY", -12000, "\"single\": true"), "veh_00050:Fendt 942 Vario", "veh_00051:Kipper");
        assertThat(entry(1).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_AMBIGUOUS);
        assertThat(entry(2).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_AMBIGUOUS);
        assertThat(entry(2).getVehicleNames()).isEqualTo("Fendt 942 Vario, Kipper");
    }

    @Test
    void aPurchaseWithoutVehicleStaysWithoutNameAfterTheWindow() {
        export(1, "");
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true"));
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true"));
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true"));
        assertThat(entry(1).getVehicleMatch()).isEqualTo(BookingEntry.MATCH_NONE);
        // a vehicle appearing later is not assigned any more
        export(2, line(1, "SHOP_VEHICLE_BUY", -90000, "\"single\": true"), "veh_00050:Fendt 942 Vario");
        assertThat(entry(1).getVehicleNames()).isNull();
    }

    @Test
    void theApiShowsTheRequestedMonthWithClassAndSellPointName() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        export(3, line(1, "SOLD_PRODUCTS", 300, "\"fillType\": \"WHEAT\", \"sellPoint\": \"MillNorth\", \"liters\": 1400")
                + "," + "{ \"seq\": 2, \"gameTime\": 5000, \"year\": 2, \"period\": 9, \"day\": 1, \"category\": "
                + "\"SHOP_VEHICLE_BUY\", \"amount\": -90000, \"count\": 1, \"single\": true }");
        StatementView latest = controller.statement(null, null);
        assertThat(latest.available()).isTrue();
        assertThat(latest.months()).extracting(m -> m.period()).containsExactly(8, 9);
        assertThat(latest.period()).isEqualTo(9);
        assertThat(latest.entries()).extracting(StatementEntryView::financeClass).containsExactly("INVESTMENT");
        List<StatementEntryView> august = controller.statement(2, 8).entries();
        assertThat(august).hasSize(1);
        assertThat(august.getFirst().sellPointName()).isEqualTo("Mühle Nord");
        assertThat(august.getFirst().financeClass()).isEqualTo("OPERATING_INCOME");
    }
}
