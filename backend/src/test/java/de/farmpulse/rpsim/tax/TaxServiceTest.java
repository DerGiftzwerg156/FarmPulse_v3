package de.farmpulse.rpsim.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TaxYear;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TaxYearRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-E1: tax year = FS25 year. Without a calendar anchor a game month is one game day: day 11 = period 12,
 * day 12 = period 1 (next FS25 year).
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class TaxServiceTest {

    @Autowired Fixtures fx;
    @Autowired TaxService tax;
    @Autowired TaxYearRepository years;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        props.getFormulas().getTax().setAuditProbability(0);
    }

    @AfterEach
    void restore() {
        props.getFormulas().getTax().setAuditProbability(new RpsimProperties.Tax().getAuditProbability());
    }

    /** Twelve months of year 1: 10,000 € harvest income and 2,000 € fuel per month, plus taxes paid (excluded). */
    private static String yearOne() {
        return IntStream.rangeClosed(1, 12).mapToObj(p -> "{ \"year\": 1, \"period\": " + p
                + ", \"byType\": { \"HARVEST_INCOME\": 10000, \"PURCHASE_FUEL\": -2000, \"RPSIM_TAX_PAYMENT\": -500 } }")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void month(int day, int year, int period) {
        long t = GameTime.days(day);
        sg.setCurrentGameTime(t);
        fx.snapshot(sg, t, 100_000, TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 100_000, year, period, yearOne()));
        tax.onMonth(new GameMonthPassedEvent(sg.getId(), day, t));
    }

    private List<ServiceCase> bills(String kind) {
        return tax.bills(sg).stream().filter(b -> kind.equals(b.getReference())).toList();
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void theCalculationIsTraceable() {
        TaxService.Calculation c = TaxService.calculate(120_000, -24_000, 40_500, 0, 20_000, 0.25, 0);
        assertThat(c.profit()).isEqualTo(55_500);
        assertThat(c.taxable()).isEqualTo(35_500);
        assertThat(c.tax()).isEqualTo(8_875);
        assertThat(TaxService.calculate(10_000, -9_000, 0, 0, 20_000, 0.25, 0).tax()).as("below the allowance").isZero();
        assertThat(TaxService.calculate(120_000, -24_000, 40_500, 0, 20_000, 0.25, 0.1).tax()).isEqualTo(7_987);
    }

    @Test
    void afterTheFirstYearComesAnAssessmentAndThenPrepayments() {
        month(11, 1, 12);
        assertThat(years.findBySavegameAndTaxYear(sg, 1)).isPresent();
        month(12, 2, 1);
        TaxYear y = years.findBySavegameAndTaxYear(sg, 1).orElseThrow();
        assertThat(y.getStatus()).isEqualTo(TaxYear.ASSESSED);
        // income 120,000, fuel -24,000 (the own tax payments do not count), depreciation 10 % of 405,000
        assertThat(y.getOperatingIncome()).isEqualTo(120_000);
        assertThat(y.getOperatingExpense()).isEqualTo(-24_000);
        assertThat(y.getDepreciation()).isEqualTo(40_500);
        assertThat(y.getTax()).isEqualTo(1_045); // (55,500 - 50,000 allowance) x 19 %
        assertThat(bills(TaxService.ASSESSMENT)).singleElement().satisfies(b -> assertThat(b.getOfferAmount()).isEqualTo(1_045));
        assertThat(bills(TaxService.PREPAYMENT)).as("quarter 1 of year 2 = 1,045 / 4")
                .singleElement().satisfies(b -> assertThat(b.getOfferAmount()).isEqualTo(261));
        assertThat(messages()).contains("TAX_ASSESSMENT", "TAX_PREPAYMENT");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals("TAX_ASSESSMENT"))
                .findFirst().orElseThrow().getFactsJson()).contains("\"profit\":55500", "\"taxable\":5500");
        assertThat(tax.overview(sg).lastAssessment().getTax()).isEqualTo(1_045);
    }

    @Test
    void billsArePaidByButtonAndGetLateFeesRemindersAndAnEnforcementThreat() {
        month(11, 1, 12);
        month(12, 2, 1);
        ServiceCase assessment = bills(TaxService.ASSESSMENT).getFirst();
        ServiceCase prepayment = bills(TaxService.PREPAYMENT).getFirst();
        tax.pay(sg, prepayment.getId());
        assertThat(prepayment.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).filteredOn(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .anyMatch(o -> o.getPayloadJson().contains("\"amount\":-261") && o.getPayloadJson().contains("TAX_PAYMENT"));
        assertThatThrownBy(() -> tax.pay(sg, prepayment.getId())).isInstanceOf(BusinessRuleException.class);
        // 14 days to pay; one game month = one day here
        sg.setCurrentGameTime(assessment.getDeadlineGameTime() + GameTime.hours(1));
        tax.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(assessment.getCostAmount()).isEqualTo(10); // 1 % of 1,045
        assertThat(messages()).last().isEqualTo("TAX_REMINDER");
        sg.setCurrentGameTime(assessment.getDeadlineGameTime() + GameTime.days(1) + GameTime.hours(1));
        tax.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(messages()).last().isEqualTo("TAX_ENFORCEMENT");
        tax.pay(sg, assessment.getId());
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("\"amount\":-20")
                && o.getPayloadJson().contains("FINE"));
    }

    @Test
    void theTaxAdvisorLowersTheTaxAndIsAContract() {
        Contract offer = tax.offerAdvisor(sg);
        assertThat(offer.getStatus()).isEqualTo(ContractStatus.OFFERED);
        tax.acceptAdvisor(sg, offer.getId());
        assertThat(tax.advisor(sg)).isPresent();
        month(11, 1, 12);
        month(12, 2, 1);
        TaxYear y = years.findBySavegameAndTaxYear(sg, 1).orElseThrow();
        assertThat(y.getAdvisorReduction()).isEqualTo(105);
        assertThat(y.getTax()).isEqualTo(940);
        tax.cancelAdvisor(sg, offer.getId());
        assertThat(tax.advisor(sg)).isEmpty();
    }

    @Test
    void theHarshModeIsStricter() {
        sg.setTonePreset(TonePreset.HARSH);
        assertThat(tax.rate(sg)).isEqualTo(0.25);
        assertThat(tax.allowance(sg)).isEqualTo(25_000);
    }

    @Test
    void anAuditDisputesUnknownExpensesAndJumpMonths() {
        List<FinanceJournalService.Month> months = new ArrayList<>();
        for (int p = 1; p <= 12; p++) {
            List<FinanceJournalService.Line> lines = new ArrayList<>();
            lines.add(new FinanceJournalService.Line("PURCHASE_FUEL", -1000, FinanceClass.OPERATING_EXPENSE));
            if (p == 6) {
                lines.add(new FinanceJournalService.Line("MY_MOD_COSTS", -4000, FinanceClass.OPERATING_EXPENSE));
            }
            months.add(new FinanceJournalService.Month(1, p, p, true, lines));
        }
        // unknown 4,000 + excess of month 6 (5,000 - average 1,333.33) = 7,666.67 x 50 % x 25 %
        assertThat(tax.auditClaim(new TaxService.YearSums(12, 0, -16_000, months), 0.25)).isEqualTo(958);
        props.getFormulas().getTax().setAuditProbability(1);
        month(11, 1, 12);
        month(12, 2, 1);
        TaxYear y = years.findBySavegameAndTaxYear(sg, 1).orElseThrow();
        assertThat(y.getAuditStatus()).isEqualTo("ANNOUNCED");
        sg.setCurrentGameTime(y.getAuditResultGameTime() + 1);
        tax.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(y.getAuditStatus()).isEqualTo("DONE");
        assertThat(messages()).contains("TAX_AUDIT_ANNOUNCED", "TAX_AUDIT_RESULT");
    }

    @Test
    void withoutJournalNothingIsAssessed() {
        long t = GameTime.days(11);
        sg.setCurrentGameTime(t);
        fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"calendar\": { \"period\": 12, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": 1, \"monotonicDay\": 11 }"));
        tax.onMonth(new GameMonthPassedEvent(sg.getId(), 11, t));
        t = GameTime.days(12);
        sg.setCurrentGameTime(t);
        fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"calendar\": { \"period\": 1, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": 2, \"monotonicDay\": 12 }"));
        tax.onMonth(new GameMonthPassedEvent(sg.getId(), 12, t));
        assertThat(years.findBySavegameAndTaxYear(sg, 1).orElseThrow().getStatus()).isEqualTo(TaxYear.NO_DATA);
        assertThat(tax.bills(sg)).isEmpty();
    }
}
