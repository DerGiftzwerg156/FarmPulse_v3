package de.farmpulse.rpsim.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-B5: bank early warning and harvest record from the booking journal. */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class FinanceNarrationServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired Fixtures fx;
    @Autowired FinanceNarrationService finance;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired LoanService loans;

    Savegame sg;
    Character cooperative;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.bank(sg);
        cooperative = fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Rohde");
    }

    /** Journal of year 1, periods 1..n with the given operating results (as SOLD_PRODUCTS / AI); current = n + 1. */
    private FarmFacts journal(long... results) {
        String periods = IntStream.range(0, results.length).mapToObj(i -> "{ \"year\": 1, \"period\": " + (i + 1)
                + ", \"byType\": { \"" + (results[i] >= 0 ? "SOLD_PRODUCTS" : "AI") + "\": " + results[i] + " } }")
                .collect(Collectors.joining(",", "[", "]"));
        return JSON.readValue(TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), 1_000, 50_000, 1,
                results.length + 1, periods), FarmFacts.class);
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void theBankWarnsOncePerLossStreakWhileALoanRuns() {
        loans.create(sg, 50_000, 0.05, 24, "Traktor", false, null);
        finance.evaluate(sg, journal(5_000, -1_000, -2_000));
        assertThat(messages()).containsExactly("BANK_CASHFLOW_WARNING");
        NarrationJob job = jobs.findBySavegameOrderByIdAsc(sg).getFirst();
        assertThat(job.getFactsJson()).contains("\"negativeMonths\":2").contains("\"financeOperatingResult\":-2000");
        // the streak goes on: no second warning
        finance.evaluate(sg, journal(5_000, -1_000, -2_000, -500));
        assertThat(messages()).hasSize(1);
        // a positive month ends the streak, the next streak warns again
        finance.evaluate(sg, journal(5_000, -1_000, -2_000, -500, 800, -100, -100));
        assertThat(messages()).containsExactly("BANK_CASHFLOW_WARNING", "BANK_CASHFLOW_WARNING");
    }

    @Test
    void noWarningWithoutABankLoanOrForASingleLossMonth() {
        finance.evaluate(sg, journal(5_000, -1_000, -2_000));
        assertThat(messages()).isEmpty();
        loans.create(sg, 50_000, 0.05, 24, "Traktor", false, null);
        finance.evaluate(sg, journal(5_000, -1_000, -2_000, 300, -700));
        assertThat(messages()).isEmpty();
    }

    @Test
    void theCooperativeCongratulatesOnARecordMonthAfterSomeHistory() {
        // the first months only build the history (record-min-months = 3)
        finance.evaluate(sg, journal(10_000, 12_000, 11_000));
        assertThat(messages()).isEmpty();
        finance.evaluate(sg, journal(10_000, 12_000, 11_000, 12_000));
        assertThat(messages()).as("equal to the record is no record").isEmpty();
        finance.evaluate(sg, journal(10_000, 12_000, 11_000, 12_000, 30_000));
        assertThat(messages()).containsExactly("COOPERATIVE_RECORD_HARVEST");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getFirst().getFactsJson()).contains("\"financeRevenue\":30000");
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(cooperative))
                .extracting(e -> e.getReason()).contains(TrustReason.RECORD_HARVEST);
        finance.evaluate(sg, journal(10_000, 12_000, 11_000, 12_000, 30_000, 20_000));
        assertThat(messages()).hasSize(1);
    }

    @Test
    void manyMonthsAtOnceOnlyBuildTheHistory() {
        loans.create(sg, 50_000, 0.05, 24, "Traktor", false, null);
        // record in an old month and a loss streak that already ended: nothing to tell
        finance.evaluate(sg, journal(1_000, 1_000, 1_000, 90_000, -5, -5, 2_000));
        assertThat(messages()).isEmpty();
        assertThat(sg.getFinMonthsSeen()).isEqualTo(7);
        assertThat(sg.getFinRecordRevenue()).isEqualTo(90_000);
    }

    @Test
    void withoutJournalNothingHappens() {
        finance.evaluate(sg, JSON.readValue(TestData.farmFacts(sg.getBridgeSavegameId(), 1_000, 1), FarmFacts.class));
        assertThat(messages()).isEmpty();
        assertThat(sg.getFinLastMonthKey()).isNull();
    }
}
