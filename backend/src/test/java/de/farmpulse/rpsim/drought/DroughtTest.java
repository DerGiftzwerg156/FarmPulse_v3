package de.farmpulse.rpsim.drought;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Drought;
import de.farmpulse.rpsim.domain.GrowingFieldMonth;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.RainPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.GrowingFieldMonthRepository;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.RainPeriodRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-W1..W3: drought detection, drought aid and the weather-index insurance. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DroughtTest {

    static final long DAY = GameTime.days(1);
    /** Calendar: 1 day per month, month index 0 = March - index 14 = May, 15 = June (growth months 3 and 4). */
    static final long MAY = 14;
    static final long JUNE = 15;
    /** Own field 7 (WHEAT 4.5 ha, growing), neighbour fields: WHEAT 10 ha, BARLEY 6 ha, CANOLA 3 ha, POTATO 2 ha. */
    static final String FIELDS = """
            "fields": [{ "farmlandId": 12, "name": "7", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 2,
                         "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 }],
            "npcFields": [{ "farmlandId": 20, "name": "20", "hectares": 10, "fruitType": "WHEAT", "growthState": 3,
                            "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 },
                          { "farmlandId": 21, "name": "21", "hectares": 6, "fruitType": "BARLEY", "growthState": 5,
                            "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 },
                          { "farmlandId": 22, "name": "22", "hectares": 3, "fruitType": "CANOLA", "growthState": 2,
                            "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 },
                          { "farmlandId": 23, "name": "23", "hectares": 2, "fruitType": "POTATO", "growthState": 2,
                            "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 },
                          { "farmlandId": 24, "name": "24", "hectares": 50, "fruitType": "OAT", "growthState": 9,
                            "minHarvestingGrowthState": 5, "maxHarvestingGrowthState": 7 }]""";
    static final String CONTEXT = """
            { "savegameId": "%s", "mapName": "Erlengrund",
              "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT", "BARLEY"] },
                             { "id": "MillSouth", "name": "Mühle Süd", "acceptedFillTypes": ["WHEAT", "CANOLA", "POTATO"] }],
              "fillTypes": ["WHEAT", "BARLEY", "CANOLA", "POTATO"], "farmlands": [] }""";

    @Autowired Fixtures fx;
    @Autowired DroughtService drought;
    @Autowired InsuranceService insurance;
    @Autowired ContractActions actions;
    @Autowired RainPeriodRepository rain;
    @Autowired GrowingFieldMonthRepository growing;
    @Autowired MarketEventRepository events;
    @Autowired ServiceCaseRepository cases;
    @Autowired ContractRepository contracts;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Erlengrund");
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Herr Amtmann");
        fx.character(sg, CharacterRole.INSURANCE_AGENT, CharacterCategory.MANDATORY, "Frau Sicher");
        fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Erna Klatsch");
        facts();
    }

    private void facts() {
        String base = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000);
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.withFields(base, FIELDS));
    }

    /** Rain time of a month: observed share of the month (1 day) and rain share of the observed time. */
    private void rain(long month, double observedShare, double rainShare) {
        RainPeriod rp = new RainPeriod();
        rp.setSavegame(sg);
        rp.setMonthIndex(month);
        rp.setObservedMs(Math.round(DAY * observedShare));
        rp.setRainMs(Math.round(DAY * observedShare * rainShare));
        rain.save(rp);
    }

    private void growingField(long month, int farmlandId, double hectares) {
        GrowingFieldMonth g = new GrowingFieldMonth();
        g.setSavegame(sg);
        g.setMonthIndex(month);
        g.setFarmlandId(farmlandId);
        g.setFieldName(String.valueOf(farmlandId));
        g.setFruitType("WHEAT");
        g.setHectares(hectares);
        growing.save(g);
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private String facts(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals(type)).findFirst()
                .orElseThrow().getFactsJson();
    }

    private List<OutboxInstruction> money() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(i -> i.getType() == InstructionType.MONEY_TRANSACTION).toList();
    }

    private List<ServiceCase> aid() {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.DROUGHT_AID));
    }

    private Contract droughtInsurance() {
        Contract c = insurance.offer(sg, InsuranceService.DROUGHT);
        return insurance.accept(sg, c.getId());
    }

    // ------------------------------------------------------------------------------------------ W1 rating

    @Test
    void aGrowthMonthIsDryBelowTheRainShareAndUnknownWithTooFewSamples() {
        RpsimProperties.Drought cfg = props.getFormulas().getDrought();
        assertThat(DroughtService.rate(new DroughtService.MonthRain(0.029, 0.5), cfg)).isEqualTo(DroughtService.MonthRating.DRY);
        assertThat(DroughtService.rate(new DroughtService.MonthRain(0.03, 1), cfg)).isEqualTo(DroughtService.MonthRating.WET);
        assertThat(DroughtService.rate(new DroughtService.MonthRain(0.0, 0.49), cfg)).isEqualTo(DroughtService.MonthRating.UNKNOWN);
        assertThat(DroughtService.rate(new DroughtService.MonthRain(null, 0), cfg)).isEqualTo(DroughtService.MonthRating.UNKNOWN);
        rain(MAY, 0.6, 0);
        rain(MAY - 3, 1, 0); // February: outside the growth months
        assertThat(drought.rate(sg, MAY)).isEqualTo(DroughtService.MonthRating.DRY);
        assertThat(drought.rate(sg, MAY - 3)).isEqualTo(DroughtService.MonthRating.OUTSIDE);
        assertThat(drought.rate(sg, JUNE)).isEqualTo(DroughtService.MonthRating.UNKNOWN);
    }

    @Test
    void theCooperativeWarnsAtTheFirstDryMonthAndTheAgentOffersTheDroughtCover() {
        rain(MAY, 1, 0.01);
        drought.onMonth(new GameMonthPassedEvent(sg.getId(), MAY + 1, (MAY + 1) * DAY));
        assertThat(sg.getDroughtDryMonths()).isEqualTo(1);
        assertThat(sg.getDroughtSeriesStartMonth()).isEqualTo(MAY);
        assertThat(narrations()).containsExactly("DROUGHT_WARNING", "DROUGHT_INSURANCE_OFFER");
        assertThat(facts("DROUGHT_WARNING")).contains("\"month\":\"Mai\"").contains("\"rainPercent\":1.0");
        assertThat(facts("DROUGHT_INSURANCE_OFFER")).contains("\"monthlyPremium\":18").contains("\"payout\":900")
                .contains("\"afterWarning\":true");
        Contract offer = contracts.findBySavegameOrderByIdDesc(sg).getFirst();
        assertThat(offer.getStatus()).isEqualTo(ContractStatus.OFFERED);
        assertThat(offer.getLevel()).isEqualTo(InsuranceService.DROUGHT);
        assertThat(offer.getMonthlyAmount()).isEqualTo(18); // 4.5 ha x 4 EUR
        assertThat(drought.list(sg)).isEmpty();
    }

    @Test
    void twoDryGrowthMonthsDeclareOneDroughtWithRegionalHarvestFailures() {
        rain(MAY, 1, 0);
        rain(JUNE, 0.8, 0.02);
        rain(JUNE + 1, 1, 0);
        growingField(MAY, 12, 4.5);
        growingField(JUNE, 12, 4.5);
        growingField(JUNE, 13, 6); // sown in June, counted once
        drought.evaluate(sg, MAY);
        drought.evaluate(sg, JUNE);
        drought.evaluate(sg, JUNE + 1); // the series goes on - no second drought

        Drought d = drought.list(sg).getFirst();
        assertThat(drought.list(sg)).hasSize(1);
        assertThat(d.getFirstMonthIndex()).isEqualTo(MAY);
        assertThat(d.getLastMonthIndex()).isEqualTo(JUNE);
        // 3 largest crops standing in the village: WHEAT 14.5 ha, BARLEY 6 ha (ripe), CANOLA 3 ha; withered OAT not
        assertThat(d.getCrops()).isEqualTo("WHEAT,BARLEY,CANOLA");
        List<MarketEvent> failures = events.findBySavegameOrderByIdDesc(sg);
        assertThat(failures).allMatch(e -> e.getEventType() == MarketEventType.HARVEST_FAILURE && e.isAnnounced())
                .extracting(e -> e.getSellPoint() + "|" + e.getFillType())
                .containsExactlyInAnyOrder("MillNorth|WHEAT", "MillSouth|WHEAT", "MillNorth|BARLEY", "MillSouth|CANOLA");
        assertThat(d.getPriceEvents()).isEqualTo(4);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).filteredOn(i -> i.getType() == InstructionType.PRICE_EVENT)
                .hasSize(4);
        assertThat(narrations()).containsExactly("DROUGHT_WARNING", "DROUGHT_INSURANCE_OFFER", "DROUGHT_DECLARED",
                "FIELD_GOSSIP", "DROUGHT_AID_OFFER");
        assertThat(facts("DROUGHT_DECLARED")).contains("\"dryMonths\":2").contains("\"priceEvents\":4");
        assertThat(facts("FIELD_GOSSIP")).contains("\"topic\":\"DROUGHT\"").contains("\"fieldName\":\"7\"");
        // W2: 4.5 + 6 ha x 150 EUR, no drought insurance
        ServiceCase sc = aid().getFirst();
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getHectares()).isEqualTo(10.5);
        assertThat(sc.getOfferAmount()).isEqualTo(1575);
        assertThat(sc.getCostAmount()).isZero();
        assertThat(sc.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime() + 15 * DAY);
        assertThat(d.getAidCaseId()).isEqualTo(sc.getId());
        assertThat(d.getInsuranceResult()).isEqualTo(Drought.NONE);
        assertThat(sg.getDroughtDryMonths()).isEqualTo(3);
    }

    @Test
    void pairsWithAnOpenEventAreSkippedAndAWetMonthEndsTheSeries() {
        drought.declare(sg, MAY, JUNE);
        assertThat(events.findBySavegameOrderByIdDesc(sg)).hasSize(4);
        drought.declare(sg, MAY, JUNE);
        assertThat(events.findBySavegameOrderByIdDesc(sg)).hasSize(4); // all pairs busy
        assertThat(drought.list(sg).getFirst().getCrops()).isNull();

        rain(MAY, 1, 0);
        rain(JUNE, 1, 0.2);
        drought.evaluate(sg, MAY);
        assertThat(drought.evaluate(sg, JUNE)).isEqualTo(DroughtService.MonthRating.WET);
        assertThat(sg.getDroughtDryMonths()).isZero();
        assertThat(sg.getDroughtSeriesStartMonth()).isNull();
    }

    @Test
    void anUnknownMonthBreaksTheSeries() {
        rain(MAY, 1, 0);
        rain(JUNE, 0.4, 0); // less than half of June observed
        drought.evaluate(sg, MAY);
        assertThat(drought.evaluate(sg, JUNE)).isEqualTo(DroughtService.MonthRating.UNKNOWN);
        assertThat(drought.list(sg)).isEmpty();
        assertThat(sg.getDroughtDryMonths()).isZero();
    }

    @Test
    void noAidCaseWithoutGrowingFields() {
        drought.declare(sg, MAY, JUNE);
        assertThat(aid()).isEmpty();
        assertThat(drought.list(sg).getFirst().getAidHectares()).isZero();
    }

    // ------------------------------------------------------------------------------------------ W2 aid

    @Test
    void ownGrowingFieldsAreRecordedPerMonth() {
        drought.onFacts(new BridgeEvents.FactsIngested(sg.getId(), null, sg.getCurrentGameTime(), false));
        long month = 10; // game time 10 days
        assertThat(growing.findBySavegameAndMonthIndexBetweenOrderByFarmlandIdAsc(sg, month, month))
                .singleElement().satisfies(g -> {
                    assertThat(g.getFarmlandId()).isEqualTo(12);
                    assertThat(g.getHectares()).isEqualTo(4.5);
                    assertThat(g.getFruitType()).isEqualTo("WHEAT");
                });
        assertThat(drought.aidHectares(sg, month, month)).isEqualTo(4.5);
    }

    @Test
    void theAidIsPaidAtOnceOnApplicationAndLapsesAfterTheDeadline() {
        growingField(MAY, 12, 4.5);
        drought.declare(sg, MAY, JUNE);
        ServiceCase sc = aid().getFirst();
        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getPayoutAmount()).isEqualTo(675);
        OutboxInstruction paid = money().getLast();
        assertThat(json.readTree(paid.getPayloadJson()).get("reason").asString()).isEqualTo("SUBSIDY");
        assertThat(json.readTree(paid.getPayloadJson()).get("amount").asLong()).isEqualTo(675);
        assertThatThrownBy(() -> actions.acceptCase(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);

        drought.declare(sg, MAY, JUNE);
        ServiceCase late = aid().getFirst();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + 16 * DAY);
        drought.onDay(new GameDayPassedEvent(sg.getId(), 26, sg.getCurrentGameTime()));
        assertThat(late.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(late.getResolution()).isEqualTo("DEADLINE_MISSED");
    }

    // ------------------------------------------------------------------------------------------ W3 insurance

    @Test
    void theDroughtInsurancePaysWithoutAClaimWhenConcludedBeforeTheFirstDryMonth() {
        Contract c = droughtInsurance(); // concluded at day 10, the series starts in May (day 14)
        growingField(MAY, 12, 4.5);
        drought.declare(sg, MAY, JUNE);
        Drought d = drought.list(sg).getFirst();
        assertThat(d.getInsuranceResult()).isEqualTo(Drought.PAID);
        assertThat(d.getInsuranceContractId()).isEqualTo(c.getId());
        assertThat(d.getInsurancePayout()).isEqualTo(900); // 4.5 ha x 200 EUR
        assertThat(money()).anySatisfy(i -> {
            assertThat(json.readTree(i.getPayloadJson()).get("reason").asString()).isEqualTo("INSURANCE_PAYOUT");
            assertThat(json.readTree(i.getPayloadJson()).get("amount").asLong()).isEqualTo(900);
        });
        assertThat(narrations()).contains("DROUGHT_INSURANCE_PAYOUT");
        // W2: 50 % deduction with a drought insurance
        ServiceCase sc = aid().getFirst();
        assertThat(sc.getOfferAmount()).isEqualTo(338);
        assertThat(sc.getCostAmount()).isEqualTo(337);
    }

    @Test
    void aDroughtInsuranceConcludedTooLateOrNotPaidUpDoesNotPay() {
        droughtInsurance(); // day 10: the series of month 2 (day 2) started before
        drought.declare(sg, 2, 3);
        assertThat(drought.list(sg).getFirst().getInsuranceResult()).isEqualTo(Drought.TOO_LATE);

        insurance.activeDrought(sg).orElseThrow().setPaymentOverdue(true);
        drought.declare(sg, MAY, JUNE);
        assertThat(drought.list(sg).getFirst().getInsuranceResult()).isEqualTo(Drought.COVER_SUSPENDED);
        assertThat(money()).noneMatch(i -> i.getPayloadJson().contains("INSURANCE_PAYOUT"));
    }

    @Test
    void theDroughtCoverRunsBesideStormAndHailAndItsPremiumFollowsTheArea() {
        Contract d = droughtInsurance();
        assertThat(insurance.active(sg)).isEmpty(); // no storm/hail cover
        Contract basic = insurance.accept(sg, insurance.offer(sg, "BASIC").getId());
        assertThat(insurance.active(sg)).contains(basic);
        assertThat(insurance.activeDrought(sg)).contains(d);
        assertThat(d.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThatThrownBy(() -> insurance.offer(sg, InsuranceService.DROUGHT)).isInstanceOf(BusinessRuleException.class);

        // the own area grows to 10.5 ha: the next premium is 42 EUR
        String base = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000)
                .replace("\"farmland\": [{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]",
                        "\"farmland\": [{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 },"
                                + " { \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000 }]");
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 100_000, base);
        sg.setCurrentGameTime(d.getNextDueGameTime());
        drought.onGameTime(new GameTimeAdvancedEvent(sg.getId(), sg.getCurrentGameTime(), sg.getCurrentGameTime()));
        assertThat(d.getMonthlyAmount()).isEqualTo(42);

        actions.cancel(sg, d.getId());
        assertThat(d.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(facts("INSURANCE_CANCELLED")).contains("Dürreversicherung");
        assertThat(insurance.active(sg)).contains(basic);
    }

    @Test
    void noDroughtOfferWithoutOwnFields() {
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 100_000, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime(), 100_000).replace("{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }", ""));
        assertThatThrownBy(() -> insurance.offer(sg, InsuranceService.DROUGHT)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("eigene Felder");
        assertThat(insurance.maybeOfferDrought(sg)).isEmpty();
    }
}
