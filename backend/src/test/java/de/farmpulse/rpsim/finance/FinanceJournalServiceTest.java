package de.farmpulse.rpsim.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-B2 / R2-B3: income statement from the booking journal, boundaries of the cash-flow window. */
class FinanceJournalServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FinanceJournalService service = new FinanceJournalService(new RpsimProperties());

    /** Current month = year 2, period 8, monotonic day 20 (1 day per period); now = start of day 20 + offset. */
    private static FarmFacts facts(long now, String periods) {
        return JSON.readValue(TestData.farmFactsWithJournal("sg", now, 1000, 2, 8, periods), FarmFacts.class);
    }

    private static final String JOURNAL = """
            [{ "year": 2, "period": 5, "byType": { "HARVEST_INCOME": 1000 } },
             { "year": 2, "period": 6, "byType": { "HARVEST_INCOME": 30000, "PURCHASE_FUEL": -4000,
                                                    "SHOP_PROPERTY_BUY": -250000, "RPSIM_CREDIT_INSTALLMENT": -3000 } },
             { "year": 2, "period": 7, "byType": { "SOLD_PRODUCTS": 12000, "AI": -2000, "LEASING_COSTS": -1500,
                                                    "RPSIM_DAMAGE": -9000 } },
             { "year": 2, "period": 8, "byType": { "SOLD_PRODUCTS": 99000 } }]""";

    @Test
    void classesComeFromTheConfigurationAndUnknownCountByTheirSign() {
        assertThat(service.classify("HARVEST_INCOME", 5)).isEqualTo(FinanceClass.OPERATING_INCOME);
        assertThat(service.classify("SHOP_PROPERTY_BUY", -5)).isEqualTo(FinanceClass.INVESTMENT);
        assertThat(service.classify("RPSIM_CREDIT_DISBURSEMENT", 5)).isEqualTo(FinanceClass.FINANCING);
        assertThat(service.classify("RPSIM_DAMAGE", -5)).isEqualTo(FinanceClass.IGNORE);
        assertThat(service.classify("SOME_MOD_TYPE", 5)).isEqualTo(FinanceClass.OPERATING_INCOME);
        assertThat(service.classify("UNKNOWN", -5)).isEqualTo(FinanceClass.OPERATING_EXPENSE);
    }

    @Test
    void monthsAreCompleteBeforeTheCurrentCalendarMonth() {
        var months = service.months(facts(GameTime.days(20), JOURNAL));
        assertThat(months).extracting(FinanceJournalService.Month::period).containsExactly(5, 6, 7, 8);
        assertThat(months).extracting(FinanceJournalService.Month::complete).containsExactly(true, true, true, false);
        var june = months.get(1);
        assertThat(june.operatingResult()).isEqualTo(26_000);
        assertThat(june.sum(FinanceClass.INVESTMENT)).isEqualTo(-250_000);
        assertThat(june.sum(FinanceClass.FINANCING)).isEqualTo(-3_000);
    }

    @Test
    void cashflowIsTheAverageOfTheCompleteMonthsThatStartedInTheWindow() {
        long now = GameTime.days(20) + GameTime.hours(6);
        // window 3 days: months that started after now-3d = days 18, 19 -> periods 6 and 7 (period 5 started on day 17)
        var cashflow = service.monthlyOperatingCashflow(facts(now, JOURNAL), now, GameTime.days(3));
        // (26,000 + 8,500) / 2 - the machine/property purchase, the installment and the damage do not count
        assertThat(cashflow).hasValueSatisfying(v -> assertThat(v).isCloseTo(17_250, within(1e-6)));
        assertThat(service.monthlyLeasingCost(facts(now, JOURNAL), now, GameTime.days(3)))
                .hasValueSatisfying(v -> assertThat(v).isCloseTo(750, within(1e-6)));
    }

    @Test
    void theWindowIsLimitedToTheMonthsInTheJournal() {
        long now = GameTime.days(20);
        var cashflow = service.monthlyOperatingCashflow(facts(now, JOURNAL), now, GameTime.days(30));
        assertThat(cashflow).hasValueSatisfying(v -> assertThat(v).isCloseTo((1_000 + 26_000 + 8_500) / 3.0, within(1e-6)));
    }

    @Test
    void noCompleteMonthInTheWindowMeansNoJournalCashflow() {
        long now = GameTime.days(20) + GameTime.hours(12);
        // the current month started 12 h ago; a 1-day window reaches back into period 7 but period 7 started earlier
        assertThat(service.monthlyOperatingCashflow(facts(now, JOURNAL), now, GameTime.days(1))).isEmpty();
        // exactly one full month length after the current month start: period 7 counts
        assertThat(service.monthlyOperatingCashflow(facts(now, JOURNAL), now, GameTime.days(1) + GameTime.hours(12)))
                .hasValueSatisfying(v -> assertThat(v).isCloseTo(8_500, within(1e-6)));
        assertThat(service.monthlyOperatingCashflow(facts(now, "[{ \"year\": 2, \"period\": 8, \"byType\": {} }]"),
                now, GameTime.days(30))).isEmpty();
    }

    @Test
    void withoutJournalOrCalendarNothingIsDerived() {
        FarmFacts old = JSON.readValue(TestData.farmFacts("sg", 0, 1), FarmFacts.class);
        assertThat(service.hasJournal(old)).isFalse();
        assertThat(service.months(old)).isEmpty();
        assertThat(service.monthlyOperatingCashflow(old, 0, GameTime.days(30))).isEmpty();
        FarmFacts noCalendar = JSON.readValue(TestData.withFields(TestData.farmFacts("sg", 0, 1),
                "\"finances\": { \"periods\": " + JOURNAL + " }"), FarmFacts.class);
        assertThat(service.months(noCalendar)).extracting(FinanceJournalService.Month::complete)
                .containsExactly(true, true, true, false);
        assertThat(service.monthlyOperatingCashflow(noCalendar, 0, GameTime.days(30))).isEmpty();
    }
}
