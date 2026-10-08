package de.farmpulse.rpsim.investor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.credit.CreditFormula;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.diary.PaymentDelayService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.FarmReport;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.InvestorPeriod;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.finance.LiquidityPlanService;
import de.farmpulse.rpsim.negotiation.FarmlandBypassEvent;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.FarmReportRepository;
import de.farmpulse.rpsim.repository.FieldCropHistoryRepository;
import de.farmpulse.rpsim.repository.FieldRecordRepository;
import de.farmpulse.rpsim.repository.InvestorContractRepository;
import de.farmpulse.rpsim.repository.InvestorObligationRepository;
import de.farmpulse.rpsim.repository.InvestorPaymentRepository;
import de.farmpulse.rpsim.repository.InvestorPeriodRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.tablet.CalendarPlanService;
import de.farmpulse.rpsim.tablet.TaskService;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import de.farmpulse.rpsim.villagelife.FarmHolidayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V3.2 R32-I1..I6: investor offers, packages, deliveries, staged breaches, end of term, bank view. One game
 * day per FS25 period: month m starts at day m and is period m % 12 + 1 of FS25 year m / 12 + 1.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class InvestorTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired InvestorOfferService offers;
    @Autowired InvestorService investors;
    @Autowired InvestorLedger ledger;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired CreditScoringService scoring;
    @Autowired LiquidityPlanService plan;
    @Autowired CalendarPlanService calendar;
    @Autowired TaskService tasks;
    @Autowired FarmHolidayService holidays;
    @Autowired PaymentDelayService delays;
    @Autowired InvestorContractRepository contracts;
    @Autowired InvestorObligationRepository obligations;
    @Autowired InvestorPeriodRepository periods;
    @Autowired InvestorPaymentRepository payments;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired FarmReportRepository reports;
    @Autowired FieldRecordRepository fieldRecords;
    @Autowired FieldCropHistoryRepository cropHistory;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Map<String, RpsimProperties.Investor.Kind> kinds;
    double probability;
    double extension;
    double callShare;
    long balance = 3_000_000;

    @BeforeEach
    void setUp() {
        RpsimProperties.Investor cfg = props.getFormulas().getInvestor();
        kinds = new LinkedHashMap<>(cfg.getKinds());
        probability = cfg.getBaseProbability();
        extension = cfg.getExtensionProbability();
        callShare = cfg.getCallShare();
        cfg.setCallShare(0);
        sg = fx.savegame(); // day 10 = month 10 = period 11 of year 1
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.bank(sg);
        facts();
    }

    @AfterEach
    void restore() {
        RpsimProperties.Investor cfg = props.getFormulas().getInvestor();
        cfg.setKinds(kinds);
        cfg.setBaseProbability(probability);
        cfg.setExtensionProbability(extension);
        cfg.setCallShare(callShare);
    }

    /** Wheat 230, potatoes 180, milk 500 per 1000 l; silos with wheat and potatoes; a dairy stable; 10 ha. */
    private void facts() {
        long t = sg.getCurrentGameTime();
        long m = t / DAY;
        fx.snapshot(sg, t, balance, """
            { "schemaVersion": 1, "gameTime": %d, "savegameId": "%s", "liquidity": { "balance": %d },
              "assets": { "vehicles": [{ "uniqueId": "veh_1", "value": 2000000, "condition": 80 }], "placeables": [],
                          "farmland": [{ "farmlandId": 1, "hectares": 10, "price": 100000 }],
                          "animals": [{ "husbandryUniqueId": "hus_1", "type": "COW", "count": 30, "estimatedValue": 60000 }],
                          "storage": [] },
              "liabilities": { "vanillaLoan": { "active": false, "remainingAmount": 0 } },
              "prices": [{ "sellPoint": "Mill", "fillType": "WHEAT", "currentPrice": 230 },
                         { "sellPoint": "Mill", "fillType": "POTATO", "currentPrice": 180 },
                         { "sellPoint": "Dairy", "fillType": "MILK", "currentPrice": 500 }],
              "calendar": { "period": %d, "dayInPeriod": 1, "daysPerPeriod": 1, "year": %d, "monotonicDay": %d },
              "husbandries": [{ "husbandryUniqueId": "hus_1", "health": 80, "productivity": 90, "food": 100,
                                "conditions": [], "subTypes": [{ "name": "HOLSTEIN", "count": 30 }],
                                "storage": [{ "fillType": "MILK", "amount": 20000, "capacity": 50000 }] }],
              "fields": [{ "farmlandId": 1, "name": "Feld 1", "hectares": 10 }],
              "tradeStorage": [{ "fillType": "WHEAT", "amount": 500000, "freeCapacity": 100000 },
                               { "fillType": "POTATO", "amount": 100000, "freeCapacity": 100000 }] }"""
                .formatted(t, sg.getBridgeSavegameId(), balance, m % 12 + 1, m / 12 + 1, m));
    }

    private void report(int year, long operatingResult) {
        FarmReportService.Report r = new FarmReportService.Report(year, 12, List.of(), List.of(),
                new FarmReportService.Totals(operatingResult, 0, operatingResult, 0, 0, 0), null, List.of(), List.of(),
                List.of(), 0, null, null);
        FarmReport fr = new FarmReport();
        fr.setSavegame(sg);
        fr.setReportYear(year);
        fr.setCreatedGameTime(sg.getCurrentGameTime());
        fr.setReportJson(json.writeValueAsString(r));
        reports.save(fr);
    }

    /** Only one kind of investor in the draw. */
    private void onlyKind(String key) {
        props.getFormulas().getInvestor().setKinds(new LinkedHashMap<>(Map.of(key, kinds.get(key))));
    }

    private void month(long m) {
        sg.setCurrentGameTime(m * DAY);
        facts();
        offers.onMonth(new GameMonthPassedEvent(sg.getId(), m, m * DAY));
        investors.onMonth(new GameMonthPassedEvent(sg.getId(), m, m * DAY));
    }

    private void day(long t) {
        sg.setCurrentGameTime(t);
        offers.onDay(new GameDayPassedEvent(sg.getId(), t / DAY, t));
        investors.onDay(new GameDayPassedEvent(sg.getId(), t / DAY, t));
    }

    private List<OutboxInstruction> instructions() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    private OutboxInstruction last(InstructionType type) {
        return instructions().stream().filter(o -> o.getType() == type).reduce((a, b) -> b).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(OutboxInstruction o) {
        return json.readValue(o.getPayloadJson(), Map.class);
    }

    private void ack(OutboxInstruction ins) {
        ins.setStatus(InstructionStatus.APPLIED);
        investors.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), "APPLIED",
                ins.getRelatedEntityType(), ins.getRelatedEntityId(), Map.of()));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<TrustReason> trust(InvestorContract c) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c.getCharacter()).stream().map(TrustEvent::getReason)
                .filter(r -> r != TrustReason.INITIAL).toList();
    }

    /** An accepted contract from month 12 (year 2) with the given considerations. */
    private InvestorContract contract(String capitalType, int years, InvestorObligation... obs) {
        InvestorContract c = new InvestorContract();
        c.setSavegame(sg);
        c.setCaseId(0L);
        c.setCharacter(fx.character(sg, CharacterRole.INVESTOR, CharacterCategory.MANDATORY, "Ines Investor"));
        c.setKind("FOOD_CHAIN");
        c.setPackageNo(1);
        c.setAmount(1_000_000);
        c.setCapitalType(capitalType);
        c.setYears(years);
        c.setTargetReturn(0.08);
        c.setTargetValue(Math.round(1_000_000 * 0.08 * years));
        c.setStatus(InvestorContract.ACTIVE);
        c.setStartMonthIndex(12);
        c.setEndMonthIndex(12 + 12L * years - 1);
        c.setStartYear(2);
        c.setOfferedGameTime(sg.getCurrentGameTime());
        c.setAcceptedGameTime(sg.getCurrentGameTime());
        contracts.save(c);
        for (InvestorObligation o : obs) {
            o.setSavegame(sg);
            o.setContract(c);
            obligations.save(o);
        }
        return c;
    }

    private static InvestorObligation ob(String type, String fillType, Long quantity) {
        InvestorObligation o = new InvestorObligation();
        o.setType(type);
        o.setMain(true);
        o.setFillType(fillType);
        o.setQuantity(quantity);
        o.setValuePerYear(10_000);
        o.setTotalValue(30_000);
        return o;
    }

    // ------------------------------------------------------------------------------------------ formulas

    @Test
    void theAmountFollowsTheFarmAssets() {
        assertThat(offers.maxAmount(499_999)).isZero(); // below 250,000 no offer
        assertThat(offers.maxAmount(500_000)).isEqualTo(250_000);
        assertThat(offers.maxAmount(1_234_567)).isEqualTo(600_000); // rounded down to 50,000
        assertThat(offers.maxAmount(10_000_000)).isEqualTo(2_500_000); // capped
    }

    @Test
    void theChanceGrowsWithMilestonesAndReputation() {
        assertThat(offers.probability(0, VillageReputationService.Tier.NEUTRAL)).isCloseTo(0.05, within(1e-9));
        assertThat(offers.probability(2, VillageReputationService.Tier.NEUTRAL)).isCloseTo(0.09, within(1e-9));
        assertThat(offers.probability(5, VillageReputationService.Tier.GOOD)).isCloseTo(0.13, within(1e-9)); // max +0.06
        assertThat(offers.probability(0, VillageReputationService.Tier.CONTROVERSIAL)).isCloseTo(0.03, within(1e-9));
    }

    @Test
    void ratesAndAreasAreRounded() {
        assertThat(offers.profitShare(10_000, 100_000)).isEqualTo(0.10);
        assertThat(offers.profitShare(12_340, 100_000)).isEqualTo(0.125); // 0.5 % steps
        assertThat(offers.profitShare(100, 100_000)).isEqualTo(0.02); // at least 2 %
        assertThat(offers.profitShare(90_000, 100_000)).isEqualTo(0.30); // at most 30 %
        assertThat(offers.payoutRate(80_000, 1_000_000)).isEqualTo(0.08);
        assertThat(offers.payoutRate(64_440, 1_000_000)).isEqualTo(0.064);
        assertThat(offers.cropArea(10)).isEqualTo(3.0);
        assertThat(offers.cropArea(11)).isEqualTo(3.5); // 3.3 -> 0.5 ha steps
        assertThat(offers.cropArea(1)).isEqualTo(1.0); // at least 1 ha
        assertThat(offers.roundLiters(12_499)).isEqualTo(12_000);
    }

    // ------------------------------------------------------------------------------------------ I1 / I2

    @Test
    void anOfferHasTwoOrThreeEqualValuedPackages() {
        report(1, 200_000);
        onlyKind("FOOD_CHAIN");
        ServiceCase sc = offers.spawnOffer(sg).orElseThrow();
        assertThat(sc.getKind()).isEqualTo(CaseKind.INVESTOR_OFFER);
        assertThat(sc.getCharacter().getRole()).isEqualTo(CharacterRole.INVESTOR);
        assertThat(sc.getCharacter().getAffiliation()).isEqualTo("Regionale Lebensmittelkette");
        assertThat(sc.getOfferAmount()).isBetween(250_000L, 1_100_000L); // 50 % of 2.2 Mio assets
        assertThat(sc.getOfferAmount() % 50_000).isZero();
        assertThat(sc.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime() + 10 * DAY);
        assertThat(sg.getInvestorOfferYear()).isEqualTo(1);
        List<InvestorContract> packages = offers.packagesOf(sc);
        assertThat(packages).hasSize(2); // FOOD_CHAIN: W1 and W2
        assertThat(packages).extracting(c -> obligations.findByContractOrderByIdAsc(c).get(0).getType())
                .containsExactlyInAnyOrder("W1", "W2");
        for (InvestorContract c : packages) {
            assertThat(c.getYears()).isBetween(2, 5);
            assertThat(c.getStartMonthIndex()).isEqualTo(12);
            assertThat(c.getStartYear()).isEqualTo(2);
            assertThat(c.getTargetValue()).isEqualTo(Math.round(sc.getOfferAmount() * 0.08 * c.getYears()));
            List<InvestorObligation> obs = obligations.findByContractOrderByIdAsc(c);
            assertThat(obs.get(0).getFillType()).isIn("WHEAT", "POTATO");
            assertThat(obs.size()).isBetween(1, 3);
            long total = obs.stream().mapToLong(InvestorObligation::getTotalValue).sum();
            assertThat(total).isEqualTo(c.getTargetValue());
            InvestorObligation main = obs.get(0);
            double price = "WHEAT".equals(main.getFillType()) ? 230 : 180;
            if ("W1".equals(main.getType())) {
                assertThat(main.getQuantity()).isEqualTo(offers.roundLiters(main.getTotalValue() / price * 1000));
                assertThat(main.getMinPerYear()).isEqualTo(offers.roundLiters(main.getQuantity() / (double) c.getYears()));
            } else {
                assertThat(main.getQuantity()).isEqualTo(offers.roundLiters(main.getTotalValue() / (c.getYears() * 12.0)
                        / price * 1000));
            }
        }
        assertThat(narrations()).containsExactly("INVESTOR_OFFER");
    }

    @Test
    void theMonthlyTriggerNeedsAGoodYearAndPunctualPayments() {
        props.getFormulas().getInvestor().setBaseProbability(1);
        month(11);
        assertThat(offers.offers(sg)).isEmpty(); // no farm report yet
        report(1, -5_000);
        month(11);
        assertThat(offers.offers(sg)).isEmpty(); // a loss
        report(2, 200_000);
        delays.record(sg, PaymentDelay.TAX, sg.getCurrentGameTime());
        month(11);
        assertThat(offers.offers(sg)).isEmpty(); // a payment delay within 12 months
        sg.setCurrentGameTime(30 * DAY);
        month(30); // year 3, the delay is 19 months ago
        assertThat(offers.offers(sg)).hasSize(1);
        month(31); // same FS25 year: no second offer
        assertThat(offers.offers(sg)).hasSize(1);
        sg.setInvestorsEnabled(false); // settings -> events
        offers.offers(sg).get(0).setStatus(CaseStatus.DECLINED);
        month(40);
        assertThat(offers.offers(sg)).hasSize(1);
    }

    @Test
    void acceptingBooksTheCapitalAndDiscardsTheOtherPackages() {
        report(1, 200_000);
        onlyKind("FOOD_CHAIN");
        ServiceCase sc = offers.spawnOffer(sg).orElseThrow();
        List<InvestorContract> packages = offers.packagesOf(sc);
        InvestorContract c = offers.accept(sg, sc.getId(), packages.get(0).getId());
        assertThat(c.getStatus()).isEqualTo(InvestorContract.ACTIVE);
        assertThat(packages.get(1).getStatus()).isEqualTo(InvestorContract.DISCARDED);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        OutboxInstruction money = last(InstructionType.MONEY_TRANSACTION);
        assertThat(payload(money)).containsEntry("reason", "INVESTOR_CAPITAL")
                .containsEntry("amount", (int) c.getAmount());
        assertThat(narrations()).contains("INVESTOR_ACCEPTED", "INVESTOR_BANK_NOTE");
        assertThatThrownBy(() -> offers.accept(sg, sc.getId(), packages.get(1).getId()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void anIgnoredOfferCostsTrustADeclinedOneNothing() {
        report(1, 200_000);
        onlyKind("FOOD_CHAIN");
        ServiceCase declined = offers.spawnOffer(sg).orElseThrow();
        actions.declineCase(sg, declined.getId());
        assertThat(declined.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(declined.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        sg.setInvestorOfferYear(null);
        ServiceCase ignored = offers.spawnOffer(sg).orElseThrow();
        day(ignored.getDeadlineGameTime() + 1);
        assertThat(ignored.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(ignored.getCharacter()))
                .extracting(TrustEvent::getReason).contains(TrustReason.INVESTOR_OFFER_IGNORED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(declined.getCharacter()))
                .extracting(TrustEvent::getReason).doesNotContain(TrustReason.INVESTOR_OFFER_IGNORED);
    }

    // ------------------------------------------------------------------------------------------ I6 bank view

    @Test
    void aSilentPartnershipRaisesTheEquityRatioASubordinatedLoanLowersIt() {
        double before = equityRatio();
        InvestorContract silent = contract(InvestorContract.SILENT, 3);
        double withSilent = equityRatio();
        silent.setStatus(InvestorContract.ENDED);
        contract(InvestorContract.SUBORDINATED, 3);
        double withLoan = equityRatio();
        assertThat(withSilent).isGreaterThan(before);
        assertThat(withLoan).isLessThan(before);
    }

    private double equityRatio() {
        return CreditFormula.components(scoring.inputs(sg, 100_000, 60, 0.05),
                props.getFormulas().getCredit()).equityRatio();
    }

    // ------------------------------------------------------------------------------------------ I3 / I4 W2

    @Test
    void aShortfallIsRemindedMadeUpCompensatedAndTheThirdBreachTerminates() {
        InvestorContract c = contract(InvestorContract.SILENT, 3, ob("W2", "WHEAT", 20_000L));
        InvestorObligation w2 = obligations.findByContractOrderByIdAsc(c).get(0);
        month(12);
        assertThatThrownBy(() -> investors.deliver(sg, w2.getId(), 25_000, null))
                .isInstanceOf(BusinessRuleException.class); // more than due
        investors.deliver(sg, w2.getId(), 15_000, null);
        OutboxInstruction t = last(InstructionType.STORAGE_TRANSFER);
        assertThat(payload(t)).containsEntry("direction", "OUT").containsEntry("fillType", "WHEAT")
                .containsEntry("amount", 15_000);
        assertThat(instructions()).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION); // no money
        ack(t);
        ack(t); // a re-sent delivery after a rewind does not count twice

        month(13); // 5,000 l short in month 12: breach 1 -> reminder with grace
        InvestorPeriod p12 = periods.findByObligationAndPeriodKey(w2, 12).orElseThrow();
        assertThat(p12.getStatus()).isEqualTo(InvestorPeriod.REMINDED);
        assertThat(p12.getShortfall()).isEqualTo(5_000);
        ServiceCase reminder = cases.findById(p12.getReminderCaseId()).orElseThrow();
        assertThat(reminder.getKind()).isEqualTo(CaseKind.INVESTOR_REMINDER);
        assertThat(c.getBreaches()).isEqualTo(1);
        investors.deliver(sg, w2.getId(), 5_000, null); // within the grace
        ack(last(InstructionType.STORAGE_TRANSFER));
        assertThat(p12.getStatus()).isEqualTo(InvestorPeriod.MADE_UP);
        assertThat(reminder.getStatus()).isEqualTo(CaseStatus.SETTLED);

        month(14); // nothing delivered in month 13: breach 2
        InvestorPeriod p13 = periods.findByObligationAndPeriodKey(w2, 13).orElseThrow();
        day(p13.getGraceUntil() + 1);
        assertThat(p13.getStatus()).isEqualTo(InvestorPeriod.COMPENSATED);
        long compensation = Math.round(20_000 / 1000.0 * 230 * 1.25);
        assertThat(p13.getCompensation()).isEqualTo(compensation);
        assertThat(payload(last(InstructionType.MONEY_TRANSACTION))).containsEntry("reason", "INVESTOR_COMPENSATION")
                .containsEntry("amount", (int) -compensation);

        month(15); // breach 3: termination with a claim of the whole amount
        assertThat(c.getStatus()).isEqualTo(InvestorContract.TERMINATED);
        ServiceCase claim = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_CLAIM)).get(0);
        assertThat(claim.getOfferAmount()).isEqualTo(1_000_000);
        assertThat(claim.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime() + 10 * DAY);
        assertThat(trust(c)).contains(TrustReason.INVESTOR_REMINDER, TrustReason.INVESTOR_COMPENSATION,
                TrustReason.INVESTOR_TERMINATION);
        assertThat(narrations()).contains("INVESTOR_REMINDER", "INVESTOR_COMPENSATION", "INVESTOR_TERMINATION");
        assertThatThrownBy(() -> investors.deliver(sg, w2.getId(), 1_000, null)).isInstanceOf(BusinessRuleException.class);

        actions.acceptCase(sg, claim.getId()); // pay by button
        assertThat(claim.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(payload(last(InstructionType.MONEY_TRANSACTION))).containsEntry("reason", "INVESTOR_REPAYMENT")
                .containsEntry("amount", -1_000_000);
        assertThat(c.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
    }

    @Test
    void anUnpaidClaimIsRemindedEveryMonthWithoutInterest() {
        balance = 100_000;
        facts();
        InvestorContract c = contract(InvestorContract.SUBORDINATED, 2);
        investors.terminate(sg, c);
        ServiceCase claim = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_CLAIM)).get(0);
        assertThatThrownBy(() -> actions.acceptCase(sg, claim.getId())).isInstanceOf(BusinessRuleException.class);
        day(claim.getDeadlineGameTime() + DAY / 2);
        day(claim.getDeadlineGameTime() + DAY / 2 + DAY / 4); // same month: no second reminder
        assertThat(claim.getRoundsUsed()).isEqualTo(1);
        assertThat(claim.getOfferAmount()).isEqualTo(1_000_000); // no interest
        assertThat(delays.any(sg, 0, sg.getCurrentGameTime())).isTrue();
        assertThat(trust(c)).contains(TrustReason.INVESTOR_CLAIM_OVERDUE);
        assertThat(narrations()).contains("INVESTOR_CLAIM_REMINDER");
        assertThat(ledger.bankDebt(sg)).isEqualTo(1_000_000); // the bank sees the claim
    }

    // ------------------------------------------------------------------------------------------ W3 / A1 / A2 / A3 / A4

    @Test
    void milkAndAnimalsAreDeliveredFromTheChosenStableWithoutMoney() {
        InvestorObligation a1 = ob("A1", null, 5L);
        a1.setSubType("HOLSTEIN");
        InvestorContract c = contract(InvestorContract.SILENT, 2, ob("W3", "MILK", 5_000L), a1);
        List<InvestorObligation> obs = obligations.findByContractOrderByIdAsc(c);
        month(12);
        assertThatThrownBy(() -> investors.deliver(sg, obs.get(0).getId(), 5_000, "hus_9"))
                .isInstanceOf(BusinessRuleException.class);
        investors.deliver(sg, obs.get(0).getId(), 5_000, "hus_1");
        assertThat(payload(last(InstructionType.HUSBANDRY_TRANSFER))).containsEntry("husbandryUniqueId", "hus_1")
                .containsEntry("fillType", "MILK").containsEntry("amount", 5_000);
        investors.deliver(sg, obs.get(1).getId(), 5, "hus_1");
        assertThat(payload(last(InstructionType.ANIMAL_TRANSFER))).containsEntry("direction", "OUT")
                .containsEntry("subType", "HOLSTEIN").containsEntry("count", 5);
        assertThat(instructions()).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION);
        ack(last(InstructionType.HUSBANDRY_TRANSFER));
        month(13);
        assertThat(periods.findByObligationAndPeriodKey(obs.get(0), 12).orElseThrow().getStatus())
                .isEqualTo(InvestorPeriod.FULFILLED);
        // the animals were refused: the delivery does not count, A1 is short at the year change
        OutboxInstruction animals = last(InstructionType.ANIMAL_TRANSFER);
        animals.setStatus(InstructionStatus.FAILED);
        animals.setAckMessage("NOT_ENOUGH_ANIMALS");
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), animals.getInstructionId(), "FAILED",
                animals.getRelatedEntityType(), animals.getRelatedEntityId()));
        assertThat(investors.outstanding(sg, obs.get(1))).isEqualTo(5);
    }

    @Test
    void animalWelfareCropObligationAndGrowthTargetAreChecked() {
        InvestorObligation a2 = ob("A2", null, null);
        a2.setTarget(70.0);
        InvestorObligation a3 = ob("A3", "WHEAT", null);
        a3.setHectares(3.0);
        InvestorObligation a4 = ob("A4", null, null);
        a4.setTargetKind("AREA");
        a4.setTarget(12.0);
        InvestorContract c = contract(InvestorContract.SILENT, 2, a2, a3, a4);
        List<InvestorObligation> obs = obligations.findByContractOrderByIdAsc(c);
        month(12);
        investors.onFacts(new BridgeEvents.FactsIngested(sg.getId(), 1L, sg.getCurrentGameTime(), false)); // health 80
        month(13);
        assertThat(periods.findByObligationAndPeriodKey(obs.get(0), 12).orElseThrow().getStatus())
                .isEqualTo(InvestorPeriod.FULFILLED);
        month(14); // no export in month 13: no breach
        assertThat(periods.findByObligationAndPeriodKey(obs.get(0), 13).orElseThrow().getStatus())
                .isEqualTo(InvestorPeriod.NO_DATA);
        // year 2: wheat stood on field 1 (10 ha) -> A3 fulfilled; 10 ha < 12 ha -> A4 breach
        FieldRecord rec = new FieldRecord();
        rec.setSavegame(sg);
        rec.setFarmlandId(1);
        rec.setHectares(10.0);
        rec.setPhase(FieldPhase.values()[0]);
        fieldRecords.save(rec);
        FieldCropHistory h = new FieldCropHistory();
        h.setSavegame(sg);
        h.setFarmlandId(1);
        h.setCropYear(2);
        h.setFruitType("WHEAT");
        cropHistory.save(h);
        for (long m = 15; m <= 24; m++) {
            month(m);
        }
        assertThat(periods.findByObligationAndPeriodKey(obs.get(1), 2).orElseThrow().getStatus())
                .isEqualTo(InvestorPeriod.FULFILLED);
        InvestorPeriod growth = periods.findByObligationAndPeriodKey(obs.get(2), 2).orElseThrow();
        assertThat(growth.getStatus()).isEqualTo(InvestorPeriod.REMINDED);
        day(growth.getGraceUntil() + 1);
        assertThat(growth.getCompensation()).isEqualTo(Math.round(10_000 * 1.25)); // value per year x 1.25
    }

    // ------------------------------------------------------------------------------------------ money R1 / R2

    @Test
    void fixedPayoutAndProfitShareFollowTheYear() {
        props.getFormulas().getInvestor().setExtensionProbability(0);
        InvestorObligation r2 = ob("R2", null, null);
        r2.setRate(0.08);
        InvestorObligation r1 = ob("R1", null, null);
        r1.setRate(0.10);
        contract(InvestorContract.SILENT, 2, r2, r1);
        for (long m = 12; m <= 23; m++) {
            month(m);
        }
        report(2, 150_000);
        month(24); // year change after year 2
        List<Map<String, Object>> paid = instructions().stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .map(this::payload).toList();
        assertThat(paid).extracting(p -> p.get("reason")).containsOnly("INVESTOR_PAYOUT");
        assertThat(paid).extracting(p -> p.get("amount")).containsExactlyInAnyOrder(-80_000, -15_000);
        // no profit = no payment, no breach
        for (long m = 25; m <= 35; m++) {
            month(m);
        }
        report(3, -20_000);
        month(36);
        // two years of R2 (80,000 each) and one R1 (year 2), no R1 in year 3
        assertThat(instructions().stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .map(this::payload).filter(p -> "INVESTOR_PAYOUT".equals(p.get("reason"))).count()).isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------------ P1 - P5

    @Test
    void theVetoBlocksTheFieldSaleUntilTheInvestorAgrees() {
        InvestorContract c = contract(InvestorContract.SILENT, 2, ob("P1", null, null));
        assertThatThrownBy(() -> ledger.requireSellable(sg, 1)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Investoren");
        investors.fieldConsent(sg, c.getId(), 1);
        ledger.requireSellable(sg, 1);
        assertThat(narrations()).contains("INVESTOR_FIELD_CONSENT");
        // a sale of another field in the game menu is a breach
        investors.onFarmlandBypass(new FarmlandBypassEvent(sg.getId(), 2, false, null, null, 50_000));
        assertThat(c.getBreaches()).isEqualTo(1);
        investors.onFarmlandBypass(new FarmlandBypassEvent(sg.getId(), 1, false, null, null, 50_000));
        assertThat(c.getBreaches()).isEqualTo(1); // agreed field
    }

    @Test
    void firstRefusalAndVisitAreRequestedOncePerYear() {
        InvestorObligation p2 = ob("P2", "WHEAT", 100_000L);
        InvestorObligation p4 = ob("P4", null, 1L);
        InvestorContract c = contract(InvestorContract.SILENT, 2, p2, p4);
        for (long m = 12; m <= 23; m++) {
            month(m);
        }
        ServiceCase purchase = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_PURCHASE)).get(0);
        assertThat(purchase.getCostAmount()).isEqualTo(Math.round(230 * 0.9));
        assertThat(purchase.getQuantity()).isEqualTo(100_000);
        ServiceCase visit = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_VISIT)).get(0);
        assertThat(purchase.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(visit.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        actions.acceptCase(sg, purchase.getId());
        OutboxInstruction t = last(InstructionType.STORAGE_TRANSFER);
        assertThat(outbox.findByBatchId(t.getBatchId())).extracting(o -> payload(o).get("reason"))
                .contains("GOODS_SALE"); // like G2
        ack(t);
        assertThat(purchase.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(periods.findByCaseId(purchase.getId()).orElseThrow().getStatus()).isEqualTo(InvestorPeriod.FULFILLED);
        actions.declineCase(sg, visit.getId()); // no acceptance = breach
        assertThat(periods.findByCaseId(visit.getId()).orElseThrow().isBreach()).isTrue();
        assertThat(c.getBreaches()).isEqualTo(1);
    }

    @Test
    void theHolidayFlatStaysFreeInJulyAndAugust() {
        sg.setFarmHolidaySince(0L);
        contract(InvestorContract.SILENT, 2, ob("P3", null, 2L));
        FarmHolidayService.MonthResult july = holidays.compute(sg, 16); // period 5 of year 2
        assertThat(july.investor()).isTrue();
        assertThat(july.income()).isZero();
        assertThat(holidays.compute(sg, 18).investor()).isFalse(); // period 7
        assertThat(holidays.compute(sg, 4).investor()).isFalse(); // period 5 before the term
    }

    @Test
    void theNameInTheVillagePaperIsPublic() {
        report(1, 200_000);
        onlyKind("FOOD_CHAIN");
        ServiceCase sc = offers.spawnOffer(sg).orElseThrow();
        InvestorContract c = offers.packagesOf(sc).get(0);
        InvestorObligation p5 = ob("P5", null, null);
        p5.setMain(false);
        p5.setSavegame(sg);
        p5.setContract(c);
        obligations.save(p5);
        offers.accept(sg, sc.getId(), c.getId());
        assertThat(publicActions.findBySavegameOrderByGameTimeAsc(sg)).extracting(PublicActionEvent::getType,
                PublicActionEvent::getDelta).contains(org.assertj.core.groups.Tuple.tuple(PublicActionType.INVESTOR_NAMED, 2.0));
    }

    // ------------------------------------------------------------------------------------------ I5

    @Test
    void theEndIsAnnouncedAndTheSilentPartnershipBoughtBackAtFaceValue() {
        props.getFormulas().getInvestor().setExtensionProbability(0);
        InvestorContract c = contract(InvestorContract.SILENT, 2);
        for (long m = 12; m <= 32; m++) {
            month(m);
        }
        assertThat(c.isAnnounced()).isTrue(); // month 32 = end 35 - 3
        assertThat(narrations()).contains("INVESTOR_END_ANNOUNCEMENT");
        assertThat(plan.plan(sg).months()).anySatisfy(mp -> assertThat(mp.postings())
                .anyMatch(p -> p.kind().equals("INVESTOR_REPAYMENT") && p.amount() == -1_000_000));
        month(33);
        month(34);
        month(35); // last month: buy-back at face value
        assertThat(payload(last(InstructionType.MONEY_TRANSACTION))).containsEntry("reason", "INVESTOR_REPAYMENT")
                .containsEntry("amount", -1_000_000);
        assertThat(ledger.bankAssets(sg)).isZero();
        month(36);
        assertThat(c.getStatus()).isEqualTo(InvestorContract.ENDED);
        assertThat(trust(c)).contains(TrustReason.INVESTOR_CONTRACT_ENDED);
        assertThat(c.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
    }

    @Test
    void withoutMoneyTheRepaymentBecomesAClaim() {
        props.getFormulas().getInvestor().setExtensionProbability(0);
        balance = 10_000;
        facts();
        InvestorContract c = contract(InvestorContract.SUBORDINATED, 2);
        for (long m = 12; m <= 35; m++) {
            month(m);
        }
        ServiceCase claim = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_CLAIM)).get(0);
        assertThat(claim.getOfferAmount()).isEqualTo(1_000_000);
        assertThat(instructions()).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION);
        month(36);
        assertThat(c.getStatus()).isEqualTo(InvestorContract.ENDED);
        assertThat(c.getCharacter().getStatus()).isNotEqualTo(CharacterStatus.TERMINATED); // claim still open
    }

    @Test
    void anAcceptedExtensionReplacesTheRepayment() {
        props.getFormulas().getInvestor().setExtensionProbability(1);
        onlyKind("FOOD_CHAIN");
        InvestorContract c = contract(InvestorContract.SILENT, 2);
        for (long m = 12; m <= 32; m++) {
            month(m);
        }
        ServiceCase ext = offers.offers(sg).get(0);
        assertThat(ext.getExternalId()).isEqualTo(String.valueOf(c.getId()));
        assertThat(narrations()).contains("INVESTOR_EXTENSION_OFFER");
        InvestorContract next = offers.accept(sg, ext.getId(), offers.packagesOf(ext).get(0).getId());
        assertThat(next.getStartMonthIndex()).isEqualTo(36);
        assertThat(next.getStartYear()).isEqualTo(4);
        assertThat(instructions()).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION); // no money flows
        assertThat(offers.runningInvestors(sg)).isEqualTo(1);
        month(33);
        month(34);
        month(35);
        assertThat(instructions()).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION); // no repayment
        assertThat(ledger.bankAssets(sg)).isEqualTo(1_000_000); // the capital stays (now the extension)
        month(36);
        assertThat(c.getStatus()).isEqualTo(InvestorContract.ENDED);
        assertThat(c.getEndReason()).isEqualTo("EXTENDED");
        assertThat(c.getCharacter().getStatus()).isNotEqualTo(CharacterStatus.TERMINATED);
    }

    // ------------------------------------------------------------------------------------------ open payments, tasks

    @Test
    void aRefusedPaymentStaysOpenAndCanBePaidByButton() {
        InvestorObligation r2 = ob("R2", null, null);
        r2.setRate(0.05);
        InvestorContract c = contract(InvestorContract.SILENT, 2, r2);
        for (long m = 12; m <= 24; m++) {
            month(m);
        }
        OutboxInstruction payout = last(InstructionType.MONEY_TRANSACTION);
        payout.setStatus(InstructionStatus.FAILED);
        payout.setAckMessage("INSUFFICIENT_FUNDS");
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), payout.getInstructionId(), "FAILED",
                payout.getRelatedEntityType(), payout.getRelatedEntityId()));
        InvestorPayment p = payments.findByContractOrderByIdAsc(c).get(0);
        assertThat(p.getStatus()).isEqualTo(InvestorPayment.OPEN);
        assertThat(ledger.bankDebt(sg)).isEqualTo(50_000);
        investors.payOpen(sg, p.getId());
        assertThat(p.getStatus()).isEqualTo(InvestorPayment.BOOKED);
    }

    @Test
    void openDeliveriesAreTasksAndRemindedAWeekBefore() {
        InvestorContract c = contract(InvestorContract.SILENT, 2, ob("W2", "WHEAT", 20_000L));
        month(12);
        assertThat(tasks.tasks(sg).items()).anySatisfy(t -> {
            assertThat(t.type()).isEqualTo("INVESTOR_DUE");
            assertThat(t.investorDue().remaining()).isEqualTo(20_000);
            assertThat(t.deadlineGameTime()).isEqualTo(13 * DAY);
        });
        assertThat(calendar.overview(sg).agenda()).anyMatch(e -> "INVESTOR_DELIVERY".equals(e.kind()));
        day(12 * DAY + DAY / 2); // one day per month: always within the last week
        assertThat(narrations()).contains("INVESTOR_DUE_SOON");
        assertThat(instructions()).anyMatch(o -> o.getType() == InstructionType.NOTIFICATION);
        int before = narrations().size();
        day(12 * DAY + DAY * 3 / 4);
        assertThat(narrations()).hasSize(before); // once per period
        assertThat(c.getBreaches()).isZero();
    }
}
