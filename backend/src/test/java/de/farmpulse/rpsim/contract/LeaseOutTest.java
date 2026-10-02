package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.CollateralService;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CollateralStatus;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanCollateral;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.OfferResult;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.family.FamilyService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.FieldRecordRepository;
import de.farmpulse.rpsim.repository.LoanCollateralRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.NpcFieldRecordRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-L1: leasing out an own field to a neighbour. A game month is one game day here. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class LeaseOutTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired LeaseOutService leaseOut;
    @Autowired NegotiationEngine engine;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired ContractBillingService billing;
    @Autowired FamilyService family;
    @Autowired CollateralService collateral;
    @Autowired CreditScoringService scoring;
    @Autowired LoanService loans;
    @Autowired de.farmpulse.rpsim.repository.DiaryEntryRepository diaryEntries;
    @Autowired FieldRecordRepository fieldRecords;
    @Autowired NpcFieldRecordRepository npcFields;
    @Autowired LoanCollateralRepository collaterals;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired RpsimProperties props;
    @Autowired de.farmpulse.rpsim.finance.LiquidityPlanService plan;
    @Autowired JsonMapper json;

    Savegame sg;
    Character albers;
    Character jansen;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        fx.character(sg, CharacterRole.LAND_AGENT, CharacterCategory.MANDATORY, "Landhändler Kuhn");
        albers = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bäuerin Albers");
        albers.setVirtualWealth(1_000_000);
        albers.setNegotiationTrait(NegotiationTrait.NEUTRAL);
        jansen = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        jansen.setVirtualWealth(500); // cannot carry the rent
        fx.snapshot(sg, 100_000); // the player owns farmland 12 (4.5 ha, 54,000 €)
        ownership.reconcile(sg);
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<String> diaryTitles() {
        return diaryEntries.findBySavegameOrderByGameTimeAscIdAsc(sg).stream().map(d -> d.getTitle()).toList();
    }

    private List<OutboxInstruction> transfers() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)
                .toList();
    }

    private void phase(FieldPhase p) {
        FieldRecord r = fieldRecords.findBySavegameOrderByFarmlandIdAsc(sg).stream().filter(x -> x.getFarmlandId() == 12)
                .findFirst().orElseGet(FieldRecord::new);
        r.setSavegame(sg);
        r.setFarmlandId(12);
        r.setPhase(p);
        fieldRecords.save(r);
    }

    /** Agreement with Albers at the desired rent of 50 € per ha and month for 1 year. */
    private Contract leased() {
        List<Negotiation> ns = leaseOut.offer(sg, 12, 1, 50);
        Negotiation n = ns.getFirst();
        assertThat(engine.placeOffer(sg, n.getId(), n.getLastCounterOffer()).result()).isEqualTo(OfferResult.ACCEPTED);
        return leaseOut.active(sg, 12).orElseThrow();
    }

    /** A farm_facts export in which farmland 12 is (not) owned by the player farm. */
    private void exportWithField(boolean owned) {
        outbox.findBySavegameOrderByIdAsc(sg).forEach(o -> o.setStatus(InstructionStatus.APPLIED));
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000);
        if (!owned) {
            facts = facts.replace("[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]", "[]");
        }
        var s = fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, facts);
        BridgeEvents.FactsIngested e = new BridgeEvents.FactsIngested(sg.getId(), s.getId(), sg.getCurrentGameTime(), false);
        ownership.onFacts(e);
        family.onFacts(e);
    }

    private void day(long gameTime) {
        sg.setCurrentGameTime(gameTime);
        leaseOut.onDay(new GameDayPassedEvent(sg.getId(), 0, gameTime));
    }

    // ------------------------------------------------------------------------------------------ offer

    @Test
    void theGuideValueIsTheFieldPriceTimesTheShareOverTwelvePerHectare() {
        assertThat(LeaseOutService.guideRate(54_000, 4.5, props.getFormulas().getLeaseOut())).isEqualTo(50);
        LeaseOutService.Quote q = leaseOut.quote(sg, 12);
        assertThat(q.guideRate()).isEqualTo(50);
        assertThat(q.termYearsMin()).isEqualTo(1);
        assertThat(q.termYearsMax()).isEqualTo(3);
        assertThat(LeaseOutService.monthlyRent(50, 4.5)).isEqualTo(225);
    }

    @Test
    void neighboursWithEnoughCapitalBidBetween85And100PercentOfTheDesiredRent() {
        List<Negotiation> ns = leaseOut.offer(sg, 12, 2, 50);
        assertThat(ns).singleElement().satisfies(n -> {
            assertThat(n.getKind()).isEqualTo(NegotiationKind.LEASE_OFFER);
            assertThat(n.getCounterpartCharacter()).isEqualTo(albers);
            assertThat(n.getLeaseTermMonths()).isEqualTo(24);
            assertThat(n.getBasePrice()).isEqualTo(50);
            assertThat(n.getLastCounterOffer()).isBetween(42L, 50L);
        });
        assertThat(narrations()).containsExactly("LEASE_OUT_BID");
        assertThatThrownBy(() -> leaseOut.offer(sg, 12, 1, 50)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Verhandlung");
    }

    @Test
    void withoutInterestTheLandAgentWrites() {
        albers.setVirtualWealth(100);
        assertThat(leaseOut.offer(sg, 12, 1, 50)).isEmpty();
        assertThat(narrations()).containsExactly("LEASE_OUT_NO_INTEREST");
    }

    @Test
    void onlyAnEmptyOrHarvestedOwnFieldWithinTheTermRangeCanBeLeasedOut() {
        phase(FieldPhase.GROWING);
        assertThatThrownBy(() -> leaseOut.offer(sg, 12, 1, 50)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("leer oder abgeerntet");
        phase(FieldPhase.HARVESTED);
        assertThatThrownBy(() -> leaseOut.offer(sg, 12, 4, 50)).hasMessageContaining("Laufzeit");
        assertThatThrownBy(() -> leaseOut.offer(sg, 13, 1, 50)).hasMessageContaining("eigene Felder");
        Negotiation n = leaseOut.offer(sg, 12, 1, 50).getFirst();
        phase(FieldPhase.GROWING); // sown meanwhile: no agreement any more
        assertThatThrownBy(() -> engine.placeOffer(sg, n.getId(), 45)).hasMessageContaining("leer oder abgeerntet");
    }

    @Test
    void aPledgedFieldNeedsTheBanksConsent() {
        fx.bank(sg);
        Loan l = loans.create(sg, 20_000, 0.05, 12, "Stall", false, null);
        LoanCollateral c = new LoanCollateral();
        c.setSavegame(sg);
        c.setLoan(l);
        c.setFarmlandId(12);
        c.setCollateralValue(32_400);
        c.setStatus(CollateralStatus.PLEDGED);
        collaterals.save(c);
        assertThatThrownBy(() -> leaseOut.offer(sg, 12, 1, 50)).hasMessageContaining("Zustimmung zur Verpachtung");
        collateral.requestLeaseConsent(sg, 12);
        assertThat(c.isLeaseConsent()).isTrue();
        assertThat(c.getStatus()).isEqualTo(CollateralStatus.PLEDGED);
        assertThat(narrations()).contains("COLLATERAL_LEASE_CONSENT");
        assertThat(leaseOut.offer(sg, 12, 1, 50)).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ start and rent

    @Test
    void theAgreementGivesTheFieldAwayInTheGameAndTheRentComesEveryMonth() {
        Contract c = leased();
        assertThat(c.getCharacter()).isEqualTo(albers);
        assertThat(c.getTermMonths()).isEqualTo(12);
        long rate = engine.list(sg).getFirst().getFinalPrice();
        assertThat(c.getMonthlyAmount()).isEqualTo(Math.round(rate * 4.5));
        assertThat(ownership.get(sg, 12).orElseThrow().isLeasedFromPlayer()).isTrue();
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(transfers()).singleElement()
                .satisfies(o -> assertThat(o.getPayloadJson()).contains("\"farmlandId\":12", "FROM_PLAYER"));
        assertThat(narrations()).contains("LEASE_OUT_STARTED");

        fx.snapshot(sg, 0); // the tenant pays even when the account is empty
        sg.setCurrentGameTime(11 * DAY);
        billing.bill(sg);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).filteredOn(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .singleElement().satisfies(o -> assertThat(o.getPayloadJson())
                        .contains("\"amount\":" + c.getMonthlyAmount(), "LEASE_INCOME"));
    }

    @Test
    void theLiquidityPlanShowsTheRentAsKnownIncome() {
        Contract c = leased(); // first rent at day 11 = the next month
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.farmFactsWithJournal(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime(), 100_000, 1, 11, "[{ \"year\": 1, \"period\": 11, \"byType\": {} }]"));
        de.farmpulse.rpsim.finance.LiquidityPlanService.MonthPlan next = plan.plan(sg).months().getFirst();
        assertThat(next.postings()).filteredOn(p -> "CONTRACT".equals(p.kind())).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo("LEASE_OUT:12");
            assertThat(p.amount()).isEqualTo(c.getMonthlyAmount());
        });
    }

    @Test
    void theTransferIsNeitherASaleNorAPurchaseAndTheFieldStillCountsForTheBank() {
        double before = scoring.leasedOutValue(sg);
        leased();
        exportWithField(false);
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(diaryTitles()).noneMatch(t -> t.contains("Spielmenü")); // no vanilla sale (R2-D2)
        assertThat(before).isZero();
        assertThat(scoring.leasedOutValue(sg)).isEqualTo(54_000);
        assertThat(collateral.eligible(sg)).extracting(CollateralService.FieldOption::farmlandId).contains(12);
        assertThatThrownBy(() -> engine.createSaleOffer(sg, 12, 60_000)).hasMessageContaining("verpachtet");
    }

    @Test
    void theFamilyFieldCostsLessTrustThanASaleAndStaysTheFamilyField() {
        Character mother = fx.character(sg, CharacterRole.FAMILY, CharacterCategory.MANDATORY, "Mutter");
        mother.setAffiliation(FamilyService.PARENT);
        sg.setFamilyFieldId(12);
        leased();
        exportWithField(false);
        assertThat(sg.getFamilyFieldId()).isEqualTo(12);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(mother))
                .extracting(e -> e.getReason(), e -> e.getDelta())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(TrustReason.FAMILY_FIELD_LEASED, -5.0));
        assertThat(narrations()).contains("FAMILY_FIELD_LEASED").doesNotContain("FAMILY_FIELD_SOLD");
    }

    // ------------------------------------------------------------------------------------------ end of term

    @Test
    void theTenantOffersARenewalAndTheRenewalExtendsTheTerm() {
        Contract c = leased(); // ends at day 22
        day(20 * DAY);
        assertThat(c.getRenewalAmount()).isNull();
        day(21 * DAY);
        assertThat(c.isRenewalOffered()).isTrue();
        assertThat(c.getRenewalAmount()).isBetween(Math.round(c.getMonthlyAmount() * 0.95) - 1,
                Math.round(c.getMonthlyAmount() * 1.1) + 1);
        assertThat(narrations()).contains("LEASE_OUT_ENDING");
        long renewal = c.getRenewalAmount();
        leaseOut.renew(sg, c.getId());
        assertThat(c.getMonthlyAmount()).isEqualTo(renewal);
        assertThat(c.getEndsAtGameTime()).isEqualTo(34 * DAY);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void theReturnWaitsForAnEmptyOrHarvestedFieldAtMostOneMonth() {
        Contract c = leased(); // ends at day 22
        NpcFieldRecord npc = new NpcFieldRecord();
        npc.setSavegame(sg);
        npc.setFarmlandId(12);
        npc.setPhase(FieldPhase.GROWING);
        npcFields.save(npc);
        day(22 * DAY);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThatThrownBy(() -> leaseOut.renew(sg, c.getId())).isInstanceOf(BusinessRuleException.class);
        day(23 * DAY); // one month later the field comes back anyway
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("TERM_ENDED");
        assertThat(ownership.get(sg, 12).orElseThrow().isLeasedFromPlayer()).isFalse();
        assertThat(transfers()).last().satisfies(o -> assertThat(o.getPayloadJson()).contains("TO_PLAYER"));
        assertThat(narrations()).contains("LEASE_OUT_ENDED");
    }

    @Test
    void anEmptyFieldComesBackAtTheEndOfTheTerm() {
        Contract c = leased();
        day(22 * DAY); // no neighbour-field data: at once
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
    }

    @Test
    void buyingTheFieldBackInTheGameMenuEndsTheLeaseAndAnnoysTheTenant() {
        Contract c = leased();
        exportWithField(false);
        exportWithField(true); // bought in the field menu of the game
        assertThat(c.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(c.getEndReason()).isEqualTo("RECLAIMED_IN_MENU");
        assertThat(ownership.get(sg, 12).orElseThrow().isLeasedFromPlayer()).isFalse();
        assertThat(ownership.get(sg, 12).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(albers))
                .filteredOn(e -> e.getReason() == TrustReason.LEASE_OUT_RECLAIMED).singleElement()
                .satisfies(e -> assertThat(e.getDelta()).isEqualTo(-10.0));
        assertThat(narrations()).contains("LEASE_OUT_RECLAIMED").doesNotContain("FIELD_BOUGHT_OVER_HEAD", "FIELD_GOSSIP");
        assertThat(diaryTitles()).contains("Verpachtung abgebrochen")
                .noneMatch(t -> t.contains("im Spielmenü gekauft") || t.contains("im Spielmenü verkauft"));
    }

    @Test
    void aFailedTransferMakesTheLeaseVoid() {
        Contract c = leased();
        assertThat(leaseOut.onInstructionFailed(c.getId(), InstructionType.FARMLAND_TRANSFER, false)).isTrue();
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("TRANSFER_FAILED");
        assertThat(ownership.get(sg, 12).orElseThrow().isLeasedFromPlayer()).isFalse();
        assertThat(engine.list(sg)).allSatisfy(n -> assertThat(n.getStatus()).isNotEqualTo(NegotiationStatus.OPEN));
        assertThat(c.getKind()).isEqualTo(ContractKind.LEASE_OUT);
    }
}
