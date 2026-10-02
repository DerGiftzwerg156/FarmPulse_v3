package de.farmpulse.rpsim.finance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.farmpulse.rpsim.api.Views.BarnView;
import de.farmpulse.rpsim.api.Views.StablesView;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.credit.AnnualReviewService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FarmReport;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TaxYear;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.finance.FinanceJournalService.Month;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FarmReportRepository;
import de.farmpulse.rpsim.repository.RainPeriodRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TaxYearRepository;
import de.farmpulse.rpsim.tablet.StableService;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V3 R3-K3: at the year change (period 12 -> 1) the backend writes the farm report of the finished FS25 year -
 * income and expenses per category from the booking journal, profit and tax ({@code TaxYear}, assessed just before at
 * {@code @Order(77)}), crop and yield per field ({@code FieldCropHistory}), rain hours ({@code RainPeriod}), the
 * stables, staff, trust and village reputation. The key figures of a report are the snapshot the next report compares
 * with (owner decision: the first report has no previous-year column). A diary entry follows and the bank advisor
 * invites to the annual review; the AI only comments in that mail, the figures come from here.
 */
@Service
public class FarmReportService {

    private final SavegameRepository savegames;
    private final FarmReportRepository reports;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final TaxYearRepository taxYears;
    private final FieldService fields;
    private final RainPeriodRepository rain;
    private final StableService stables;
    private final ServiceCaseRepository cases;
    private final EmployeeRepository employees;
    private final CharacterRepository characters;
    private final TrustScoreService trust;
    private final VillageReputationService reputation;
    private final DiaryService diary;
    private final AnnualReviewService annualReview;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final JsonMapper json;

    public FarmReportService(SavegameRepository savegames, FarmReportRepository reports, FactsService facts,
                             FinanceJournalService journal, TaxYearRepository taxYears, FieldService fields,
                             RainPeriodRepository rain, StableService stables, ServiceCaseRepository cases,
                             EmployeeRepository employees, CharacterRepository characters, TrustScoreService trust,
                             VillageReputationService reputation, DiaryService diary, AnnualReviewService annualReview,
                             GameTime gameTime, RpsimProperties props, JsonMapper json) {
        this.savegames = savegames;
        this.reports = reports;
        this.facts = facts;
        this.journal = journal;
        this.taxYears = taxYears;
        this.fields = fields;
        this.rain = rain;
        this.stables = stables;
        this.cases = cases;
        this.employees = employees;
        this.characters = characters;
        this.trust = trust;
        this.reputation = reputation;
        this.diary = diary;
        this.annualReview = annualReview;
        this.gameTime = gameTime;
        this.props = props;
        this.json = json;
    }

    public record CategoryLine(String category, long amount) {
    }

    public record Totals(long operatingIncome, long operatingExpense, long operatingResult, long investment,
                         long divestment, long financing) {
    }

    /** Tax of the year; {@code status} OPEN / ASSESSED of the TaxYear, null without a tax year. */
    public record TaxLine(String status, Long profit, Long tax) {
    }

    /** {@code yieldLiters} null = not recorded (crops before R3-K3, or not harvested). */
    public record FieldLine(int farmlandId, String fruitType, Double hectares, boolean harvested, boolean withered,
                            Long yieldLiters) {
    }

    public record RainLine(int period, double rainHours, double observedHours) {
    }

    public record StableLine(String type, int count, Double health, Double productivity) {
    }

    public record TrustLine(Long characterId, String name, String role, String level) {
    }

    /** The key figures the next report compares with. */
    public record Snapshot(int staff, long monthlyWages, int animals, Double averageHealth, String reputationTier,
                           List<TrustLine> trust) {
    }

    public record Report(int year, int months, List<CategoryLine> income, List<CategoryLine> expenses, Totals totals,
                         TaxLine tax, List<FieldLine> fields, List<RainLine> rain, List<StableLine> stables,
                         int welfareInspections, Snapshot snapshot, Snapshot previous) {
    }

    /** Year change: write the report of the finished year once, then the diary entry and the invitation. */
    @EventListener
    @Order(85)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.calendar() == null || f.calendar().year() == null
                || gameTime.periodOfYear(sg, e.gameTime()) != 1) {
            return;
        }
        int year = f.calendar().year() - 1;
        if (reports.findBySavegameAndReportYear(sg, year).isPresent()) {
            return;
        }
        Report r = build(sg, f, year, gameTime.anchor(sg).monthIndex(e.gameTime()));
        FarmReport saved = save(sg, r);
        diary.addAuto(sg, "CREDIT", "Hofbericht " + year, "Der Hofbericht für das Jahr " + year
                + " liegt in der App „Bank“: Betriebsergebnis " + LoanEuro.format(r.totals().operatingResult())
                + (r.tax() != null && r.tax().tax() != null ? ", Steuer " + LoanEuro.format(r.tax().tax()) : "") + ".",
                "FARM_REPORT", saved.getId());
        annualReview.invite(sg, r);
    }

    FarmReport save(Savegame sg, Report r) {
        FarmReport fr = new FarmReport();
        fr.setSavegame(sg);
        fr.setReportYear(r.year());
        fr.setCreatedGameTime(sg.getCurrentGameTime());
        fr.setReportJson(json.writeValueAsString(r));
        return reports.save(fr);
    }

    /** The report of {@code year}; {@code firstMonthOfNextYear} = month index of period 1 after it. */
    Report build(Savegame sg, FarmFacts f, int year, long firstMonthOfNextYear) {
        List<Month> months = journal.months(f).stream().filter(m -> m.year() == year && m.complete()).toList();
        Map<String, Double> income = new LinkedHashMap<>();
        Map<String, Double> expenses = new LinkedHashMap<>();
        double[] sums = new double[6];
        for (Month m : months) {
            for (FinanceJournalService.Line l : m.lines()) {
                FinanceClass c = l.financeClass();
                switch (c) {
                    case OPERATING_INCOME -> {
                        income.merge(l.category(), l.amount(), Double::sum);
                        sums[0] += l.amount();
                    }
                    case OPERATING_EXPENSE -> {
                        expenses.merge(l.category(), l.amount(), Double::sum);
                        sums[1] += l.amount();
                    }
                    case INVESTMENT -> sums[2] += l.amount();
                    case DIVESTMENT -> sums[3] += l.amount();
                    case FINANCING -> sums[4] += l.amount();
                    default -> {
                        // IGNORE: insurance payouts and damages stay out of the report like out of the journal
                    }
                }
            }
        }
        Totals totals = new Totals(Math.round(sums[0]), Math.round(sums[1]), Math.round(sums[0] + sums[1]),
                Math.round(sums[2]), Math.round(sums[3]), Math.round(sums[4]));
        TaxLine tax = taxYears.findBySavegameAndTaxYear(sg, year)
                .map(t -> new TaxLine(t.getStatus(), TaxYear.ASSESSED.equals(t.getStatus()) ? t.getProfit() : null,
                        TaxYear.ASSESSED.equals(t.getStatus()) ? t.getTax() : null))
                .orElse(null);

        Map<Integer, Double> hectares = new LinkedHashMap<>();
        for (FieldRecord rec : fields.records(sg)) {
            hectares.put(rec.getFarmlandId(), rec.getHectares());
        }
        List<FieldLine> fieldLines = fields.cropsOfYear(sg, year).stream()
                .sorted(Comparator.comparingInt(FieldCropHistory::getFarmlandId).thenComparing(FieldCropHistory::getFruitType))
                .map(h -> new FieldLine(h.getFarmlandId(), h.getFruitType(), hectares.get(h.getFarmlandId()), h.isHarvested(),
                        h.isWithered(), h.getYieldLiters() == null ? null : Math.round(h.getYieldLiters())))
                .toList();

        List<RainLine> rainLines = new ArrayList<>();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        for (long idx = firstMonthOfNextYear - 12; idx < firstMonthOfNextYear; idx++) {
            long monthIndex = idx;
            rain.findBySavegameAndMonthIndex(sg, monthIndex).ifPresent(p -> rainLines.add(new RainLine(
                    anchor.periodOf(monthIndex), p.getRainMs() / 3_600_000.0, p.getObservedMs() / 3_600_000.0)));
        }

        StablesView sv = stables.stables(sg);
        List<StableLine> stableLines = sv.barns().stream()
                .map(b -> new StableLine(b.type(), b.count(), b.health(), b.productivity())).toList();
        long yearStart = anchor.monthStart(firstMonthOfNextYear - 12);
        long yearEnd = anchor.monthStart(firstMonthOfNextYear);
        int inspections = (int) cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.AUTHORITY_INSPECTION))
                .stream().filter(c -> "ANIMAL_WELFARE".equals(c.getTitle()))
                .filter(c -> c.getGameTime() >= yearStart && c.getGameTime() < yearEnd).count();

        Snapshot snapshot = snapshot(sg, sv);
        Snapshot previous = reports.findBySavegameOrderByReportYearDesc(sg).stream()
                .filter(p -> p.getReportYear() < year).findFirst()
                .map(p -> json.readValue(p.getReportJson(), Report.class).snapshot()).orElse(null);
        return new Report(year, months.size(), lines(income), lines(expenses), totals, tax, fieldLines, rainLines,
                stableLines, inspections, snapshot, previous);
    }

    private static List<CategoryLine> lines(Map<String, Double> m) {
        return m.entrySet().stream().map(e -> new CategoryLine(e.getKey(), Math.round(e.getValue())))
                .sorted(Comparator.comparingLong((CategoryLine l) -> -Math.abs(l.amount())).thenComparing(CategoryLine::category))
                .toList();
    }

    Snapshot snapshot(Savegame sg, StablesView sv) {
        List<Employee> staff = employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE);
        List<TrustLine> trustLines = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(Character::getId))
                .map(c -> new TrustLine(c.getId(), c.getName(), c.getRole() == null ? null : c.getRole().name(),
                        TrustScoreService.displayLevel(trust.getCurrentTrust(c), props.getFormulas().getTrust())))
                .toList();
        Double health = sv.barns().stream().map(BarnView::health).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
        return new Snapshot(staff.size(), staff.stream().mapToLong(Employee::getMonthlySalary).sum(), sv.animals(),
                health == null ? null : Math.round(health * 10) / 10.0, reputation.tier(sg).name(), trustLines);
    }

    public List<Report> list(Savegame sg) {
        return reports.findBySavegameOrderByReportYearDesc(sg).stream()
                .map(r -> json.readValue(r.getReportJson(), Report.class)).toList();
    }

    public Optional<Report> latest(Savegame sg) {
        return list(sg).stream().findFirst();
    }

    /** German euro text for the diary (NumberFormat is not thread-safe - one per call). */
    static final class LoanEuro {
        static String format(long amount) {
            return java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(amount) + " €";
        }
    }
}
