package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CollateralStatus;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.CreditReasonCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanCollateral;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TaxYear;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.finance.LiquidityPlanService;
import de.farmpulse.rpsim.negotiation.FarmlandBypassEvent;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.FieldCropHistoryRepository;
import de.farmpulse.rpsim.repository.LoanCollateralRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TaxYearRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-K: collateral (K1), liquidity plan (K2), farm report and annual review (K3). */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class CreditPlanningTest {

    static final long DAY = GameTime.days(1);
    /** Own fields 12 (54,000 €), 14 (500,000 €) and 15 (100,000 €). */
    static final String FARMLAND = """
            "farmland": [{ "farmlandId": 12, "hectares": 4.5, "price": 54000 },
                         { "farmlandId": 14, "hectares": 40, "price": 500000 },
                         { "farmlandId": 15, "hectares": 8, "price": 100000 }]""";

    @Autowired Fixtures fx;
    @Autowired CreditApplicationService applications;
    @Autowired CollateralService collateral;
    @Autowired LoanCollateralRepository collaterals;
    @Autowired LoanService loans;
    @Autowired NegotiationEngine negotiations;
    @Autowired ContractActions actions;
    @Autowired AnnualReviewService annualReview;
    @Autowired FarmReportService reports;
    @Autowired LiquidityPlanService plan;
    @Autowired FieldService fields;
    @Autowired FieldCropHistoryRepository crops;
    @Autowired FarmlandOwnershipRepository ownership;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired DiaryEntryRepository diary;
    @Autowired EmployeeRepository employees;
    @Autowired TaxYearRepository taxYears;
    @Autowired CreditConfigResolver configs;
    @Autowired JsonMapper json;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        // calendar anchor: month index 0 = period 1 (March) at game time 0, one day per period
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        bank = fx.bank(sg);
        own(12, 4.5, 54_000);
        own(14, 40, 500_000);
        own(15, 8, 100_000);
        facts(1_000_000, "");
    }

    private void own(int farmlandId, double hectares, long price) {
        FarmlandOwnership o = new FarmlandOwnership();
        o.setSavegame(sg);
        o.setFarmlandId(farmlandId);
        o.setOwnerType(OwnerType.PLAYER);
        o.setHectares(hectares);
        o.setReferencePrice(price);
        o.setTradeable(true);
        ownership.save(o);
    }

    private void facts(long balance, String extra) {
        String base = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance)
                .replace("\"farmland\": [{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]", FARMLAND);
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, extra.isEmpty() ? base : TestData.withFields(base, extra));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<TrustReason> trust() {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(bank).stream().map(TrustEvent::getReason).toList();
    }

    private List<OutboxInstruction> instructions() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    /** A running loan with a Grundschuld on the given fields (collateral value = price x 0.6). */
    private Loan pledgedLoan(long principal, double rate, int... farmlandIds) {
        Loan l = loans.create(sg, principal, rate, 60, "Halle", true, null);
        for (int id : farmlandIds) {
            LoanCollateral c = new LoanCollateral();
            c.setSavegame(sg);
            c.setLoan(l);
            c.setFarmlandId(id);
            c.setCollateralValue(id == 12 ? 32_400 : id == 14 ? 300_000 : 60_000);
            c.setStatus(CollateralStatus.PLEDGED);
            collaterals.save(c);
        }
        return l;
    }

    private CollateralStatus status(int farmlandId) {
        return collaterals.findAll().stream().filter(c -> c.getSavegame().getId().equals(sg.getId())
                && c.getFarmlandId() == farmlandId).reduce((a, b) -> b).map(LoanCollateral::getStatus).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ K1

    @Test
    void collateralLowersTheRateProportionallyToTheCoverage() {
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        assertThat(CreditFormula.coverage(32_400, 50_000)).isEqualTo(0.648);
        assertThat(CreditFormula.coverage(90_000, 50_000)).isEqualTo(1.0);
        assertThat(CreditFormula.interestDiscount(1.0, cfg)).isEqualTo(0.01);
        // the bonus eases "loan too large for the farm" (capped at 100)
        var weak = new CreditFormula.Inputs(1000, true, 0, 1000, 100_000, 0, 50_000, 100_000, 70, 0, 0);
        var secured = new CreditFormula.Inputs(1000, true, 0, 1000, 100_000, 0, 50_000, 100_000, 70, 0, 50_000);
        assertThat(CreditFormula.components(secured, cfg).loanToFarmSize() - CreditFormula.components(weak, cfg).loanToFarmSize())
                .isEqualTo(10.0); // 20 points x coverage 0.5

        CreditApplication plain = applications.submit(sg, 50_000, "Traktor", 60, List.of());
        CreditApplication secured2 = applications.submit(sg, 50_000, "Traktor", 60, List.of(12));
        assertThat(secured2.getCollateralValue()).isEqualTo(32_400);
        assertThat(secured2.getInterestDiscount()).isCloseTo(0.00648, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(plain.getOfferedInterestRate() - secured2.getOfferedInterestRate()).isCloseTo(0.00648,
                org.assertj.core.data.Offset.offset(1e-9));
        // the field is tied to the open application - it cannot be offered twice
        assertThatThrownBy(() -> applications.submit(sg, 10_000, "Zaun", 12, List.of(12)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Feld 12");
    }

    @Test
    void aLargeLoanNeedsAGrundschuldAndTheBankNamesTheFields() {
        CreditApplication a = applications.submit(sg, 700_000, "Stallneubau", 120, List.of());
        assertThat(a.isCollateralRequired()).isTrue();
        assertThat(a.getDecision()).isEqualTo(CreditDecision.COUNTER_OFFER);
        assertThat(collateral.ofApplication(a)).singleElement().satisfies(c -> {
            assertThat(c.getFarmlandId()).isEqualTo(14); // largest collateral value first
            assertThat(c.getStatus()).isEqualTo(CollateralStatus.PROPOSED);
        });
        sg.setCurrentGameTime(a.getDecisionVisibleAtGameTime());
        applications.releaseVisibleDecisions(sg);
        assertThat(narrations()).contains("CREDIT_COUNTER_OFFER");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("collateralNote", "Feld 14");

        applications.acceptCounterOffer(sg, a.getId());
        assertThat(status(14)).isEqualTo(CollateralStatus.PLEDGED);
        assertThat(collateral.pledged(sg)).singleElement().satisfies(c -> assertThat(c.getLoan().getId()).isEqualTo(a.getLoanId()));
    }

    @Test
    void withoutEnoughFieldsTheBankDecidesNormally() {
        CreditApplication a = applications.submit(sg, 5_000_000, "Biogas", 120, List.of());
        assertThat(a.isCollateralRequired()).isFalse();
        assertThat(collateral.ofApplication(a)).isEmpty();
        assertThat(a.getReasonCategory()).isNotEqualTo(CreditReasonCategory.CREDIT_BLOCKED);
    }

    @Test
    void aPledgedFieldIsSoldOnlyWithConsentAndTheProceedsRepay() {
        Loan l = pledgedLoan(100_000, 0.05, 12);
        assertThatThrownBy(() -> negotiations.createSaleOffer(sg, 12, 60_000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Grundschuld");

        collateral.requestSaleConsent(sg, 12);
        assertThat(narrations()).contains("COLLATERAL_SALE_CONSENT");
        collateral.requireSellable(sg, 12); // no exception any more

        collateral.onSold(sg, 12, "batch_test");
        OutboxInstruction repayment = instructions().getLast();
        assertThat(repayment.getBatchId()).isEqualTo("batch_test");
        assertThat(repayment.getPayloadJson()).contains("CREDIT_SPECIAL_REPAYMENT", "-32400");
        assertThat(l.getRemainingAmount()).isEqualTo(67_600);
        assertThat(status(12)).isEqualTo(CollateralStatus.RELEASED);
        assertThat(loans.history(l)).noneMatch(p -> p.getType() == LoanPaymentType.PREPAYMENT_FEE);

        // the mod refuses the sale batch: debt and Grundschuld are back
        loans.onBookingFailed(sg, l.getId(), repayment.getInstructionId(), "CREDIT_SPECIAL_REPAYMENT");
        assertThat(l.getRemainingAmount()).isEqualTo(100_000);
        assertThat(status(12)).isEqualTo(CollateralStatus.PLEDGED);
    }

    @Test
    void aMenuSaleOfAPledgedFieldTriggersAClaimThatBlocksNewCreditsWhenOverdue() {
        Loan l = pledgedLoan(100_000, 0.05, 12);
        collateral.onFarmlandBypass(new FarmlandBypassEvent(sg.getId(), 12, false, OwnerType.PLAYER, null, 54_000));
        assertThat(status(12)).isEqualTo(CollateralStatus.RELEASED);
        assertThat(trust()).contains(TrustReason.COLLATERAL_SOLD);
        ServiceCase claim = collateral.claims(sg).getFirst();
        assertThat(claim.getKind()).isEqualTo(CaseKind.COLLATERAL_CLAIM);
        assertThat(claim.getOfferAmount()).isEqualTo(32_400);
        assertThat(narrations()).contains("COLLATERAL_CLAIM");

        sg.setCurrentGameTime(claim.getDeadlineGameTime());
        collateral.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(claim.getResolution()).isEqualTo(CollateralService.OVERDUE);
        assertThat(claim.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(l.getMissedInstallments()).isEqualTo(1);
        assertThat(trust()).contains(TrustReason.COLLATERAL_CLAIM_OVERDUE);
        assertThat(narrations()).contains("COLLATERAL_CLAIM_OVERDUE");
        assertThat(applications.submit(sg, 10_000, "Zaun", 12, List.of()).getReasonCategory())
                .isEqualTo(CreditReasonCategory.CREDIT_BLOCKED);

        // a second day does not count again
        collateral.onDay(new GameDayPassedEvent(sg.getId(), 1, sg.getCurrentGameTime() + DAY));
        assertThat(l.getMissedInstallments()).isEqualTo(1);

        actions.acceptCase(sg, claim.getId());
        assertThat(claim.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(l.getRemainingAmount()).isEqualTo(67_600);
        assertThat(collateral.hasOverdueClaim(sg)).isFalse();
    }

    @Test
    void theBankRealisesPledgedFieldsOnACallBackOnlyInTheHarshMode() {
        Loan realistic = pledgedLoan(100_000, 0.05, 12, 14);
        loans.callBack(sg, realistic, configs.forSavegame(sg));
        assertThat(collateral.ofLoan(realistic)).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(CollateralStatus.RELEASED));
        assertThat(instructions()).noneMatch(i -> i.getType() == InstructionType.FARMLAND_TRANSFER);

        sg.setTonePreset(TonePreset.HARSH);
        Loan harsh = pledgedLoan(100_000, 0.05, 15, 14);
        loans.callBack(sg, harsh, configs.forSavegame(sg));
        // field 14 (300,000) covers the debt alone; 15 is released, the surplus stays with the player
        assertThat(collateral.ofLoan(harsh)).extracting(LoanCollateral::getFarmlandId, LoanCollateral::getStatus)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(15, CollateralStatus.RELEASED),
                        org.assertj.core.groups.Tuple.tuple(14, CollateralStatus.REALISED));
        List<OutboxInstruction> list = instructions();
        assertThat(list).anyMatch(i -> i.getType() == InstructionType.FARMLAND_TRANSFER
                && i.getPayloadJson().contains("\"farmlandId\":14") && i.getPayloadJson().contains("FROM_PLAYER"));
        assertThat(list).anyMatch(i -> i.getPayloadJson().contains("FARMLAND_SALE") && i.getPayloadJson().contains("300000"));
        assertThat(list.getLast().getPayloadJson()).contains("CREDIT_CALLBACK", "-100000");
        assertThat(ownership.findBySavegameAndFarmlandId(sg, 14).orElseThrow().getOwnerType()).isEqualTo(OwnerType.UNCLAIMED);
        assertThat(narrations()).contains("CREDIT_COLLATERAL_REALISED");
    }

    @Test
    void theGrundschuldIsReleasedWhenTheLoanIsRepaid() {
        Loan l = pledgedLoan(10_000, 0.0, 12);
        loans.specialRepayment(sg, l.getId(), 10_000);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(status(12)).isEqualTo(CollateralStatus.RELEASED);
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d ->
                assertThat(d.getTitle()).isEqualTo("Grundschuld gelöscht"));
        assertThat(collateral.eligible(sg)).extracting(CollateralService.FieldOption::farmlandId).contains(12);
    }

    // ------------------------------------------------------------------------------------------ K2

    private void journalFacts(long balance, int year, int period, String periods) {
        String base = TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance, year,
                period, periods);
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, base);
    }

    private void employee(long salary, long nextDue) {
        Employee e = new Employee();
        e.setSavegame(sg);
        e.setCharacter(fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, "Petra"));
        e.setJobRole(JobRole.MACHINE_OPERATOR);
        e.setSkill(50);
        e.setMonthlySalary(salary);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setNextSalaryDueGameTime(nextDue);
        employees.save(e);
    }

    private void assessedTax(int year, long tax) {
        TaxYear t = new TaxYear();
        t.setSavegame(sg);
        t.setTaxYear(year);
        t.setStartGameTime(0);
        t.setStatus(TaxYear.ASSESSED);
        t.setTax(tax);
        taxYears.save(t);
    }

    @Test
    void thePlanShowsTheKnownPostingsTheTaxPrepaymentAndTheEstimate() {
        sg.setCurrentGameTime(13 * DAY + GameTime.hours(2)); // month index 13 = period 2 of year 2
        assessedTax(1, 12_000);
        employee(2_000, 14 * DAY);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Stall", true, null);
        // previous year's period 3: harvest income 9,000, salaries 2,000 (added back) and diesel 1,000
        journalFacts(50_000, 2, 2, """
                [{ "year": 1, "period": 3, "byType": { "HARVEST_INCOME": 9000, "RPSIM_SALARY_PAYMENT": -2000,
                   "PURCHASE_FUEL": -1000 } },
                 { "year": 2, "period": 2, "byType": {} }]""");
        LiquidityPlanService.Plan p = plan.plan(sg);
        assertThat(p.available()).isTrue();
        assertThat(p.months()).hasSize(12);
        LiquidityPlanService.MonthPlan march = p.months().get(0); // period 3
        assertThat(march.period()).isEqualTo(3);
        assertThat(march.postings()).extracting(LiquidityPlanService.Posting::kind).containsExactly("SALARIES", "LOAN");
        assertThat(march.knownTotal()).isEqualTo(-3_000);
        assertThat(march.incomeEstimate()).isEqualTo(8_000); // 9,000 - 1,000, the salary is a known posting
        assertThat(march.incomeSource()).isEqualTo("PREVIOUS_YEAR");
        assertThat(march.reserve()).isEqualTo(3_000);
        assertThat(march.balanceEnd()).isEqualTo(55_000);
        // acceptance K: the month of the next tax prepayment with its amount (period 4, last assessment / 4)
        LiquidityPlanService.MonthPlan april = p.months().get(1);
        assertThat(april.period()).isEqualTo(4);
        assertThat(april.postings()).anySatisfy(x -> {
            assertThat(x.kind()).isEqualTo("TAX_PREPAYMENT");
            assertThat(x.amount()).isEqualTo(-3_000);
            assertThat(x.estimate()).isFalse();
        });
        assertThat(april.incomeSource()).isEqualTo("AVERAGE");
        assertThat(l.getRemainingAmount()).isEqualTo(12_000); // nothing booked
    }

    @Test
    void theAdvisorWarnsOnceAheadOfAShortfall() {
        sg.setCurrentGameTime(13 * DAY + GameTime.hours(2));
        employee(5_000, 14 * DAY);
        journalFacts(6_000, 2, 2, "[]");
        LiquidityPlanService.Plan p = plan.plan(sg);
        assertThat(p.firstBelowZero().period()).isEqualTo(4); // 6,000 - 5,000 - 5,000
        assertThat(p.firstBelowReserve().period()).isEqualTo(3);

        plan.onMonth(new GameMonthPassedEvent(sg.getId(), 13, sg.getCurrentGameTime()));
        plan.onMonth(new GameMonthPassedEvent(sg.getId(), 13, sg.getCurrentGameTime()));
        assertThat(narrations()).filteredOn("LIQUIDITY_WARNING"::equals).hasSize(1);
        assertThat(sg.getLiquidityWarningMonth()).isEqualTo(15L); // period 4

        journalFacts(500_000, 2, 2, "[]"); // the plan recovered
        plan.onMonth(new GameMonthPassedEvent(sg.getId(), 13, sg.getCurrentGameTime()));
        assertThat(sg.getLiquidityWarningMonth()).isNull();
    }

    // ------------------------------------------------------------------------------------------ K3

    @Test
    void theYieldOfAHarvestIsRecordedFromTheLastRipeSighting() throws Exception {
        String ripe = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), 0, 1), """
                "fields": [{ "farmlandId": 12, "name": "12", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 8,
                  "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "withered": false, "cut": false,
                  "fillType": "WHEAT", "litersPerSqm": 0.95 }]""");
        fields.update(sg, json.readValue(ripe, FarmFacts.class), sg.getCurrentGameTime(), 1);
        fields.update(sg, json.readValue(ripe.replace("\"growthState\": 8", "\"growthState\": 9")
                .replace("\"cut\": false", "\"cut\": true"), FarmFacts.class), sg.getCurrentGameTime() + 1000, 1);
        FieldCropHistory h = fields.cropsOfYear(sg, 1).getFirst();
        assertThat(h.isHarvested()).isTrue();
        assertThat(h.getYieldLiters()).isCloseTo(4.5 * 10_000 * 0.95, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void theYearChangeWritesTheFarmReportAndInvitesToTheAnnualReview() {
        sg.setCurrentGameTime(12 * DAY); // month index 12 = period 1 of year 2
        assessedTax(1, 4_000);
        FieldCropHistory h = new FieldCropHistory();
        h.setSavegame(sg);
        h.setFarmlandId(12);
        h.setCropYear(1);
        h.setFruitType("WHEAT");
        h.setHarvested(true);
        h.setYieldLiters(42_750.0);
        crops.save(h);
        journalFacts(80_000, 2, 1, """
                [{ "year": 1, "period": 5, "byType": { "HARVEST_INCOME": 30000, "PURCHASE_FUEL": -4000 } },
                 { "year": 1, "period": 6, "byType": { "SOLD_PRODUCTS": 5000, "SHOP_VEHICLE_BUY": -20000 } },
                 { "year": 2, "period": 1, "byType": {} }]""");
        reports.onMonth(new GameMonthPassedEvent(sg.getId(), 12, sg.getCurrentGameTime()));
        reports.onMonth(new GameMonthPassedEvent(sg.getId(), 12, sg.getCurrentGameTime())); // once per year

        FarmReportService.Report r = reports.list(sg).getFirst();
        assertThat(reports.list(sg)).hasSize(1);
        assertThat(r.year()).isEqualTo(1);
        assertThat(r.months()).isEqualTo(2);
        assertThat(r.totals().operatingIncome()).isEqualTo(35_000);
        assertThat(r.totals().operatingExpense()).isEqualTo(-4_000);
        assertThat(r.totals().investment()).isEqualTo(-20_000);
        assertThat(r.income()).extracting(FarmReportService.CategoryLine::category).containsExactly("HARVEST_INCOME", "SOLD_PRODUCTS");
        assertThat(r.tax().tax()).isEqualTo(4_000);
        assertThat(r.fields()).singleElement().satisfies(f -> assertThat(f.yieldLiters()).isEqualTo(42_750));
        assertThat(r.snapshot().reputationTier()).isNotNull();
        assertThat(r.previous()).isNull(); // the first report has no previous-year column
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d -> assertThat(d.getTitle()).isEqualTo("Hofbericht 1"));

        ServiceCase invitation = annualReview.list(sg).getFirst();
        assertThat(invitation.getKind()).isEqualTo(CaseKind.ANNUAL_REVIEW);
        assertThat(invitation.getQuantity()).isEqualTo(1);
        assertThat(narrations()).contains("ANNUAL_REVIEW_INVITATION");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("\"operatingResult\":31000");

        // the next year compares with this report
        sg.setCurrentGameTime(24 * DAY);
        journalFacts(80_000, 3, 1, "[]");
        reports.onMonth(new GameMonthPassedEvent(sg.getId(), 24, sg.getCurrentGameTime()));
        assertThat(reports.list(sg).getFirst().previous()).isEqualTo(r.snapshot());
    }

    private ServiceCase invitation(double score) {
        FarmReportService.Report r = new FarmReportService.Report(1, 12, List.of(), List.of(),
                new FarmReportService.Totals(0, 0, 0, 0, 0, 0), null, List.of(), List.of(), List.of(), 0,
                new FarmReportService.Snapshot(0, 0, 0, null, "NEUTRAL", List.of()), null);
        ServiceCase sc = annualReview.invite(sg, r);
        sc.setReference(String.valueOf(score)); // the score at the cut-off date
        return sc;
    }

    @Test
    void aGoodYearBringsARateCutThatLowersTheInstallment() {
        Loan l = loans.create(sg, 100_000, 0.05, 60, "Halle", true, null);
        long before = l.getMonthlyInstallment();
        int left = loans.remainingInstallments(l);
        ServiceCase inv = invitation(80);
        actions.acceptCase(sg, inv.getId());
        assertThat(inv.getStatus()).isEqualTo(CaseStatus.SETTLED);
        ServiceCase offer = annualReview.list(sg).stream().filter(c -> c.getKind() == CaseKind.ANNUAL_REVIEW_OFFER)
                .findFirst().orElseThrow();
        assertThat(narrations()).contains("ANNUAL_REVIEW_OFFER");

        actions.acceptCase(sg, offer.getId());
        assertThat(l.getInterestRate()).isCloseTo(0.0475, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(l.getRateCutTotal()).isCloseTo(0.0025, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(l.getMonthlyInstallment()).isLessThan(before);
        assertThat(loans.remainingInstallments(l)).isEqualTo(left); // the term stays
    }

    @Test
    void theCutIsCappedPerLoanAndByTheMinimumRate() {
        RpsimProperties.AnnualReview cfg = configs.forSavegame(sg).getAnnualReview();
        Loan capped = loans.create(sg, 10_000, 0.05, 12, "A", true, null);
        capped.setRateCutTotal(0.0095);
        Loan low = loans.create(sg, 10_000, 0.011, 12, "B", true, null);
        assertThat(AnnualReviewService.cutFor(capped, 0.0025, cfg)).isCloseTo(0.0005, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(AnnualReviewService.cutFor(low, 0.0025, cfg)).isCloseTo(0.001, org.assertj.core.data.Offset.offset(1e-9));
        capped.setRateCutTotal(0.01);
        assertThat(AnnualReviewService.cutFor(capped, 0.0025, cfg)).isZero();
    }

    @Test
    void aWeakYearOnlyBringsASeriousTalkAndDecliningHasNoConsequence() {
        loans.create(sg, 100_000, 0.05, 60, "Halle", true, null);
        ServiceCase inv = invitation(30);
        actions.acceptCase(sg, inv.getId());
        assertThat(inv.getResolution()).isEqualTo("SERIOUS");
        assertThat(narrations()).contains("ANNUAL_REVIEW_SERIOUS").doesNotContain("ANNUAL_REVIEW_OFFER");

        ServiceCase second = invitation(60);
        actions.declineCase(sg, second.getId());
        assertThat(second.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(trust()).isEmpty();

        ServiceCase third = invitation(60);
        sg.setCurrentGameTime(third.getDeadlineGameTime());
        annualReview.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(third.getStatus()).isEqualTo(CaseStatus.EXPIRED);
    }
}
