package de.farmpulse.rpsim.diary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.DiaryEntryType;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FarmReport;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Milestone;
import de.farmpulse.rpsim.domain.MilestoneKey;
import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldDocs;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.payroll.PayrollScheduler;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FarmReportRepository;
import de.farmpulse.rpsim.repository.FieldRecordRepository;
import de.farmpulse.rpsim.repository.PaymentDelayRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-T1 / R3-T2: milestones and the farm chronicle. A game month is one game day here. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ChronicleTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired MilestoneService milestones;
    @Autowired ChronicleService chronicle;
    @Autowired DiaryService diary;
    @Autowired PaymentDelayService delays;
    @Autowired LoanService loans;
    @Autowired PayrollScheduler payroll;
    @Autowired FieldService fields;
    @Autowired FieldRecordRepository fieldRecords;
    @Autowired EmployeeRepository employees;
    @Autowired DiaryEntryRepository entries;
    @Autowired PaymentDelayRepository delayRows;
    @Autowired FarmReportRepository reports;
    @Autowired RpsimProperties props;
    @Autowired TrustScoreService trust;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days = month index 10 = period 11
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.snapshot(sg, 100_000);
    }

    private void day() {
        milestones.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void yearChangeAt(long gameTime) {
        sg.setCurrentGameTime(gameTime);
        day();
        milestones.onMonth(new GameMonthPassedEvent(sg.getId(), gameTime / DAY, gameTime));
    }

    private List<MilestoneKey> reached() {
        return milestones.list(sg).stream().map(Milestone::getMilestoneKey).toList();
    }

    private List<DiaryEntry> milestoneEntries() {
        return entries.findBySavegameOrderByGameTimeAscIdAsc(sg).stream()
                .filter(e -> e.getEntryType() == DiaryEntryType.MILESTONE).toList();
    }

    private void field(int farmlandId, double hectares) {
        FieldRecord r = new FieldRecord();
        r.setSavegame(sg);
        r.setFarmlandId(farmlandId);
        r.setHectares(hectares);
        r.setPhase(FieldPhase.values()[0]);
        fieldRecords.save(r);
    }

    // ------------------------------------------------------------------------------------------ R3-T1

    @Test
    void theFirstRepaidLoanIsAMilestoneOnceWithADiaryEntryAlsoForAnOlderSavegame() {
        Loan l = loans.create(sg, 10_000, 0.05, 12, "Stall", false, null);
        l.setStatus(LoanStatus.PAID_OFF); // repaid before the update
        day();
        day();
        assertThat(reached()).containsExactly(MilestoneKey.LOAN_REPAID);
        Milestone m = milestones.list(sg).getFirst();
        assertThat(m.getReachedGameTime()).isEqualTo(sg.getCurrentGameTime());
        assertThat(milestoneEntries()).singleElement().satisfies(e -> {
            assertThat(e.getTitle()).isEqualTo("Erster Kredit getilgt");
            assertThat(e.getCategory()).isEqualTo("MILESTONE");
            assertThat(e.getId()).isEqualTo(m.getDiaryEntryId());
        });
    }

    @Test
    void areaRecordHarvestAndNeighbourTradeFollowFromStoredData() {
        field(1, 60);
        day();
        assertThat(reached()).isEmpty();
        field(2, 40);
        var coop = fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Herr Brandt");
        var neighbour = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Lenz");
        trust.recordEvent(coop, 2, TrustReason.RECORD_HARVEST, "Rekord");
        trust.recordEvent(neighbour, 3, TrustReason.NEIGHBOR_TRADE, "Weizen");
        day();
        assertThat(reached()).containsExactlyInAnyOrder(MilestoneKey.AREA, MilestoneKey.RECORD_HARVEST,
                MilestoneKey.NEIGHBOR_TRADE);
        assertThat(milestoneEntries()).extracting(DiaryEntry::getTitle)
                .contains("100 ha bewirtschaftet", "Rekordernte", "Erster Handel mit einem Nachbarn");
    }

    @Test
    void aSwitchedOffMilestoneIsNotReached() {
        RpsimProperties.Milestones cfg = props.getFormulas().getMilestones();
        cfg.setLoanRepaidEnabled(false);
        try {
            loans.create(sg, 10_000, 0.05, 12, "Stall", false, null).setStatus(LoanStatus.PAID_OFF);
            day();
            assertThat(reached()).isEmpty();
        } finally {
            cfg.setLoanRepaidEnabled(true);
        }
    }

    @Test
    void onlyAFullyObservedYearWithoutDelayCounts() {
        day(); // observation starts at day 10 (period 11)
        yearChangeAt(12 * DAY); // year 1 was not observed from its start
        assertThat(reached()).isEmpty();
        sg.setCurrentGameTime(15 * DAY);
        delays.record(sg, PaymentDelay.TAX, 15 * DAY);
        yearChangeAt(24 * DAY); // year 2 had a delay
        assertThat(reached()).isEmpty();
        yearChangeAt(36 * DAY); // year 3 without a delay
        assertThat(reached()).containsExactly(MilestoneKey.YEAR_WITHOUT_DELAY);
        assertThat(milestoneEntries()).singleElement()
                .satisfies(e -> assertThat(e.getTitle()).isEqualTo("Ein Jahr ohne Zahlungsverzug"));
    }

    @Test
    void aDelayStillOpenAtTheYearChangeCountsForTheNewYear() {
        Employee e = new Employee();
        e.setSavegame(sg);
        e.setCharacter(fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, "Petra"));
        e.setJobRole(JobRole.MACHINE_OPERATOR);
        e.setSkill(50);
        e.setMonthlySalary(500_000);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setNextSalaryDueGameTime(10 * DAY);
        employees.save(e);
        day();
        payroll.paySalaries(sg); // 100,000 € on the account: the salary stays overdue
        assertThat(e.isSalaryOverdue()).isTrue();
        assertThat(delayRows.findAll()).filteredOn(d -> d.getSavegame().getId().equals(sg.getId()))
                .extracting(PaymentDelay::getKind).containsExactly(PaymentDelay.SALARY);
        yearChangeAt(12 * DAY);
        yearChangeAt(24 * DAY); // still overdue at the start of year 2
        assertThat(reached()).isEmpty();
        assertThat(delayRows.findAll()).filteredOn(d -> d.getSavegame().getId().equals(sg.getId()))
                .extracting(PaymentDelay::getKind).contains(PaymentDelay.OPEN_AT_YEAR_START);
    }

    @Test
    void theCropRotationStreakCountsCheckedYearsAndBreaksOnARepeat() {
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
        fieldsAt(0, 1, "WHEAT");
        fieldsAt(1, 2, "BARLEY"); // year 1 closed without a previous year: neither counts nor breaks
        assertThat(sg.getRotationCleanYears()).isZero();
        fieldsAt(2, 3, "WHEAT"); // year 2: barley after wheat
        assertThat(sg.getRotationCleanYears()).isEqualTo(1);
        fieldsAt(3, 4, "WHEAT"); // year 3: wheat after barley
        assertThat(sg.getRotationCleanYears()).isEqualTo(2);
        fieldsAt(4, 5, "OAT"); // year 4: wheat again - complaint
        assertThat(sg.getRotationCleanYears()).isZero();
        sg.setRotationCleanYears(5);
        day();
        assertThat(reached()).contains(MilestoneKey.CROP_ROTATION);
        assertThat(milestoneEntries()).extracting(DiaryEntry::getTitle).contains("5 Jahre Fruchtfolge ohne Beanstandung");
    }

    private void fieldsAt(double days, int year, String crop) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.days(days);
        sg.setCurrentGameTime(t);
        var s = fx.snapshot(sg, t, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), t, year, FieldDocs.ALL_RULES, null,
                FieldDocs.field(12, crop, 8, false, false, 0, 0, 1, 1)));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    // ------------------------------------------------------------------------------------------ R3-T2

    @Test
    void theCategoryLabelsMatchTheFrontend() throws Exception {
        var frontend = json.readTree(Files.readString(Path.of("../frontend/src/app/core/i18n/de.json")))
                .get("enums").get("financeCategory");
        Properties labels = new Properties();
        try (var in = new InputStreamReader(new ClassPathResource("chronicle/de.properties").getInputStream(),
                StandardCharsets.UTF_8)) {
            labels.load(in);
        }
        assertThat(labels.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(frontend.propertyNames());
        for (String key : labels.stringPropertyNames()) {
            assertThat(labels.getProperty(key)).as(key).isEqualTo(frontend.get(key).asString());
        }
    }

    @Test
    void theFileNameUsesTheFarmNameElseTheMapName() {
        sg.setMapName(null);
        assertThat(ChronicleService.fileName(sg)).isEqualTo("chronik-FarmPulse.md");
        sg.setMapName("Riverbend Springs");
        assertThat(ChronicleService.fileName(sg)).isEqualTo("chronik-Riverbend-Springs.md");
        sg.setFarmName("Hof Sonnenschein / Nord");
        assertThat(ChronicleService.farmName(sg)).isEqualTo("Hof Sonnenschein / Nord");
        assertThat(ChronicleService.fileName(sg)).isEqualTo("chronik-Hof-Sonnenschein-Nord.md");
    }

    @Test
    void theChronicleHoldsBackstoryMilestonesEntriesAndFarmReports() {
        sg.setFarmName("Hof Lindenhain");
        diary.addAuto(sg, "BACKSTORY", "Vorgeschichte", "Der Hof gehörte schon dem Großvater.", "SAVEGAME", sg.getId());
        sg.setCurrentGameTime(11 * DAY + GameTime.hours(8));
        diary.addAuto(sg, "CREDIT", "Kredit bewilligt", "50.000 € für den Stall.", null, null);
        diary.addPlayerNote(sg, "Regentag", "Heute nur Werkstatt.\nMorgen dreschen.");
        loans.create(sg, 10_000, 0.05, 12, "Stall", false, null).setStatus(LoanStatus.PAID_OFF);
        day();
        FarmReportService.Report r = new FarmReportService.Report(1, 12,
                List.of(new FarmReportService.CategoryLine("HARVEST_INCOME", 30_000)),
                List.of(new FarmReportService.CategoryLine("PURCHASE_FUEL", -4_000)),
                new FarmReportService.Totals(30_000, -4_000, 26_000, 0, 0, 0),
                new FarmReportService.TaxLine("ASSESSED", 26_000L, 3_100L),
                List.of(new FarmReportService.FieldLine(12, "WHEAT", 4.5, true, false, 42_750L)),
                List.of(), List.of(), 0, null, null);
        FarmReport fr = new FarmReport();
        fr.setSavegame(sg);
        fr.setReportYear(1);
        fr.setCreatedGameTime(12 * DAY);
        fr.setReportJson(json.writeValueAsString(r));
        reports.save(fr);

        ChronicleService.Chronicle c = chronicle.build(sg);
        assertThat(c.farmName()).isEqualTo("Hof Lindenhain");
        assertThat(c.backstory()).isEqualTo("Der Hof gehörte schon dem Großvater.");
        assertThat(c.milestones()).extracting(ChronicleService.MilestoneLine::title).containsExactly("Erster Kredit getilgt");
        assertThat(c.days()).singleElement().satisfies(d -> {
            assertThat(d.gameDay()).isEqualTo(11);
            assertThat(d.entries()).extracting(ChronicleService.EntryLine::title)
                    .containsExactly("Kredit bewilligt", "Regentag", "Erster Kredit getilgt");
        });
        assertThat(c.reports()).singleElement().satisfies(rl -> {
            assertThat(rl.income()).extracting(ChronicleService.AmountLine::label).containsExactly("Ernteverkauf");
            assertThat(rl.fields()).extracting(ChronicleService.FieldLine::fruit).containsExactly("Weizen");
        });

        String md = ChronicleService.markdown(c);
        assertThat(md).startsWith("# Hofchronik – Hof Lindenhain\n")
                .contains("Stand: Tag 11, 08:00")
                .contains("## Vorgeschichte\n\nDer Hof gehörte schon dem Großvater.")
                .contains("## Meilensteine\n\n- Tag 11: **Erster Kredit getilgt**")
                .contains("### Tag 11")
                .contains("- 08:00 – **Regentag** _(eigene Notiz)_\n  Heute nur Werkstatt.\n  Morgen dreschen.\n")
                .contains("**Erster Kredit getilgt** _(Meilenstein)_")
                .contains("### Jahr 1", "| Einnahmen | 30.000 € |", "| Steuer | 3.100 € |",
                        "| Ernteverkauf | 30.000 € |", "| Kraftstoff | -4.000 € |", "| Feld 12 | Weizen | 4,5 ha | 42.750 l |")
                .doesNotContain("Der Hof gehörte schon dem Großvater.\n  ");
        assertThat(md.indexOf("Der Hof gehörte")).isEqualTo(md.lastIndexOf("Der Hof gehörte"));
    }
}
