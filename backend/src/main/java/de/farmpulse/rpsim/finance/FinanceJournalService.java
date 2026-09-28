package de.farmpulse.rpsim.finance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.farmpulse.rpsim.bridge.BridgeDtos.Calendar;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.FinancePeriod;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Roadmap V2 R2-B: reads the booking journal of the mod ({@code farm_facts.finances}) - sums per FS25 period (= game
 * month) and money type - and turns it into an income statement. Pure fact layer, no AI.
 * <p>
 * Without the block (older mod) every method returns "no journal" and the callers keep their V1 behaviour.
 */
@Service
public class FinanceJournalService {

    private static final Logger log = LoggerFactory.getLogger(FinanceJournalService.class);

    /** FS25 money type of leasing costs (R2-B3). */
    public static final String LEASING_COSTS = "LEASING_COSTS";

    private final RpsimProperties props;
    private final Set<String> loggedUnknown = ConcurrentHashMap.newKeySet();

    public FinanceJournalService(RpsimProperties props) {
        this.props = props;
    }

    /** One booking line of a month: category, amount (signed) and its class. */
    public record Line(String category, double amount, FinanceClass financeClass) {
    }

    /**
     * One FS25 month of the journal. {@code key} = year * 12 + period - 1 (continuous month number);
     * {@code complete} = the month is over (it lies before the current calendar month).
     */
    public record Month(int year, int period, long key, boolean complete, List<Line> lines) {

        public double sum(FinanceClass c) {
            return lines.stream().filter(l -> l.financeClass() == c).mapToDouble(Line::amount).sum();
        }

        /** Operating result = operating income + operating expenses (expenses are negative). */
        public double operatingResult() {
            return sum(FinanceClass.OPERATING_INCOME) + sum(FinanceClass.OPERATING_EXPENSE);
        }

        public double amountOf(String category) {
            return lines.stream().filter(l -> l.category().equals(category)).mapToDouble(Line::amount).sum();
        }
    }

    public static long key(int year, int period) {
        return (long) year * 12 + period - 1;
    }

    /**
     * Class of a category. Unknown categories (mods, future FS25 versions, UNKNOWN from the mod) count as operating by
     * their sign and are logged once per name.
     */
    public FinanceClass classify(String category, double amount) {
        FinanceClass c = props.getFormulas().getFinance().getCategories().get(category);
        if (c != null) {
            return c;
        }
        if (loggedUnknown.add(category)) {
            log.info("Booking journal: unknown category '{}' counts as operating by its sign - add it to "
                    + "rpsim.formulas.finance.categories to classify it", category);
        }
        return amount >= 0 ? FinanceClass.OPERATING_INCOME : FinanceClass.OPERATING_EXPENSE;
    }

    /** Roadmap V2 R2-E1: the category is classified in rpsim.formulas.finance.categories (unknown ones are disputed). */
    public boolean isKnown(String category) {
        return props.getFormulas().getFinance().getCategories().containsKey(category);
    }

    /** The journal months, oldest first; empty when the mod exports no journal. */
    public List<Month> months(FarmFacts f) {
        if (f == null || f.finances() == null || f.finances().periods() == null) {
            return List.of();
        }
        Long current = currentKey(f);
        List<FinancePeriod> periods = f.finances().periods().stream()
                .filter(p -> p != null && p.year() != null && p.period() != null)
                .sorted(Comparator.comparingLong(p -> key(p.year(), p.period()))).toList();
        long latest = periods.isEmpty() ? 0 : key(periods.getLast().year(), periods.getLast().period());
        List<Month> months = new ArrayList<>();
        for (FinancePeriod p : periods) {
            long k = key(p.year(), p.period());
            List<Line> lines = new ArrayList<>();
            Map<String, Double> byType = p.byType() == null ? Map.of() : new LinkedHashMap<>(p.byType());
            byType.forEach((category, amount) -> {
                if (amount != null && amount != 0) {
                    lines.add(new Line(category, amount, classify(category, amount)));
                }
            });
            // without calendar the latest month counts as running
            boolean complete = current != null ? k < current : k < latest;
            months.add(new Month(p.year(), p.period(), k, complete, List.copyOf(lines)));
        }
        return months;
    }

    public boolean hasJournal(FarmFacts f) {
        return f != null && f.finances() != null;
    }

    /**
     * Complete months that started within the cash-flow window {@code (now - windowMs, now]} - the months used by
     * the credit check (R2-B2). Empty without journal or calendar, or when no complete month started in the window.
     */
    public List<Month> completeMonthsInWindow(FarmFacts f, long now, long windowMs) {
        Calendar c = f == null ? null : f.calendar();
        if (!hasJournal(f) || c == null || c.period() == null || c.year() == null || c.monotonicDay() == null
                || c.daysPerPeriod() == null || c.daysPerPeriod() < 1) {
            return List.of();
        }
        long length = GameTime.days(c.daysPerPeriod());
        int dayInPeriod = c.dayInPeriod() == null ? 1 : c.dayInPeriod();
        long currentStart = GameTime.days(c.monotonicDay() - (dayInPeriod - 1));
        long count = Math.floorDiv(windowMs - Math.max(0, now - currentStart), length);
        if (count < 1) {
            return List.of();
        }
        long current = key(c.year(), c.period());
        return months(f).stream().filter(m -> m.complete() && m.key() >= current - count).toList();
    }

    /** R2-B2: average operating result per game month of the complete months in the window; empty = V1 method. */
    public Optional<Double> monthlyOperatingCashflow(FarmFacts f, long now, long windowMs) {
        List<Month> months = completeMonthsInWindow(f, now, windowMs);
        if (months.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(months.stream().mapToDouble(Month::operatingResult).average().orElse(0));
    }

    /** R2-B3: average LEASING_COSTS per game month of the same months, as a positive cost; empty = V1 estimate. */
    public Optional<Double> monthlyLeasingCost(FarmFacts f, long now, long windowMs) {
        List<Month> months = completeMonthsInWindow(f, now, windowMs);
        if (months.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Math.max(0, -months.stream().mapToDouble(m -> m.amountOf(LEASING_COSTS)).average().orElse(0)));
    }

    /** Latest complete month of the journal. */
    public Optional<Month> lastCompleteMonth(FarmFacts f) {
        return months(f).stream().filter(Month::complete).reduce((a, b) -> b);
    }

    /** Harvest revenue of a month (R2-B5 record): sum of rpsim.formulas.finance.record-categories. */
    public double harvestRevenue(Month m) {
        return props.getFormulas().getFinance().getRecordCategories().stream().mapToDouble(m::amountOf).sum();
    }

    /**
     * R2-B5: real figures of the last complete month for the narration (numbers of the fact layer, never of the AI):
     * financeMonth (German month name), financeRevenue (harvest revenue), financeOperatingIncome,
     * financeOperatingExpenses (positive) and financeOperatingResult. Nothing is added without a journal.
     */
    public NarrationFacts.Builder putFacts(NarrationFacts.Builder b, FarmFacts f) {
        lastCompleteMonth(f).ifPresent(m -> b.put("financeMonth", CalendarText.month(m.period()))
                .put("financeRevenue", Math.round(harvestRevenue(m)))
                .put("financeOperatingIncome", Math.round(m.sum(FinanceClass.OPERATING_INCOME)))
                .put("financeOperatingExpenses", Math.round(-m.sum(FinanceClass.OPERATING_EXPENSE)))
                .put("financeOperatingResult", Math.round(m.operatingResult())));
        return b;
    }

    /** Continuous month number of the current calendar month, null without calendar. */
    public static Long currentKey(FarmFacts f) {
        Calendar c = f == null ? null : f.calendar();
        if (c == null || c.year() == null || c.period() == null) {
            return null;
        }
        return key(c.year(), c.period());
    }
}
