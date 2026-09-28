package de.farmpulse.rpsim.bypass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
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

/** Roadmap V2 R2-D: the characters react to the vanilla loan, the field menu and helpers without employee. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VanillaBypassServiceTest {

    @Autowired Fixtures fx;
    @Autowired VanillaBypassService bypass;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired CreditApplicationService credit;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;

    Savegame sg;
    Character bank;
    Character owner;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
        owner = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Herr Brandt");
    }

    /** An export with the given remaining vanilla loan at the given hour offset. */
    private void loan(double hours, long remaining) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.hours(hours);
        String doc = TestData.farmFacts(sg.getBridgeSavegameId(), t, 500_000)
                .replace("\"remainingAmount\": 80000", "\"remainingAmount\": " + remaining);
        var s = fx.snapshot(sg, t, 500_000, doc);
        sg.setCurrentGameTime(Math.max(sg.getCurrentGameTime(), t));
        bypass.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private void day() {
        bypass.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<TrustEvent> trust(Character c) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c);
    }

    @Test
    void theBankAnswersAVanillaLoanWithATrustLossScaledByTheAmount() {
        loan(0, 80_000); // first export: only the reference point
        day();
        assertThat(messages()).isEmpty();
        loan(1, 110_000);
        loan(2, 112_000); // 32,000 € in one day, answered once
        day();
        assertThat(messages()).containsExactly("VANILLA_LOAN_TAKEN");
        assertThat(trust(bank)).filteredOn(e -> e.getReason() == TrustReason.VANILLA_LOAN).singleElement()
                .satisfies(e -> assertThat(e.getDelta()).isCloseTo(-3.2, within(1e-9)));
        assertThat(bypass.loanTrustDelta(1_000_000)).as("capped").isEqualTo(-8);
        assertThat(sg.isVanillaLoanSurcharge()).as("first time: no surcharge").isFalse();
    }

    @Test
    void aSecondLoanWhileOneIsOpenMakesNewCreditsMoreExpensiveUntilItIsRepaid() {
        loan(0, 80_000);
        loan(1, 100_000);
        day();
        loan(30, 120_000);
        day();
        assertThat(sg.isVanillaLoanSurcharge()).isTrue();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("\"interestSurchargePercent\":1.0");
        var application = credit.submit(sg, 10_000, "Güllefass", 12);
        if (application.getOfferedInterestRate() != null) {
            assertThat(application.getOfferedInterestRate()).isGreaterThanOrEqualTo(0.06 - 1e-9);
        }
        assertThat(bypass.interestSurcharge(sg)).isEqualTo(0.01);
        // every repayment gets an answer; the full repayment ends the surcharge
        loan(60, 110_000);
        day();
        assertThat(messages()).last().isEqualTo("VANILLA_LOAN_REPAID");
        assertThat(sg.isVanillaLoanSurcharge()).isTrue();
        loan(90, 0);
        day();
        assertThat(messages()).filteredOn("VANILLA_LOAN_REPAID"::equals).hasSize(2);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("\"fullyRepaid\":true");
        assertThat(sg.isVanillaLoanSurcharge()).isFalse();
        assertThat(sg.getVanillaLoanTakings()).isZero();
        assertThat(trust(bank)).anyMatch(e -> e.getReason() == TrustReason.VANILLA_LOAN_REPAID && e.getDelta() > 0);
    }

    @Test
    void aReloadWithoutSavingIsNoRepayment() {
        loan(0, 80_000);
        loan(5, 120_000);
        loan(2, 80_000); // reload: game time and loan went back
        day();
        assertThat(messages()).as("the pending increase went back with the reload").isEmpty();
    }

    @Test
    void switchedOffNothingButTheDiaryHappens() {
        sg.setVanillaBypassEnabled(false);
        loan(0, 80_000);
        loan(1, 150_000);
        day();
        assertThat(messages()).isEmpty();
        assertThat(trust(bank)).isEmpty();
    }

    private void boughtInTheMenu() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        fx.snapshot(sg, 100_000);
        ownership.reconcile(sg);
        ownership.setOwner(sg, 13, OwnerType.CHARACTER, owner);
        // the game now shows farmland 13 as the player's (bought in the field menu for 72,000 €)
        String doc = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime() + 1, 100_000)
                .replace("\"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000",
                        "\"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }, { \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000");
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 100_000, doc);
        ownership.reconcile(sg);
    }

    private ServiceCase claim() {
        return cases.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == CaseKind.COMPENSATION_CLAIM)
                .findFirst().orElseThrow();
    }

    @Test
    void theFormerOwnerIsAngryAndClaimsACompensationThatCanBePaid() {
        boughtInTheMenu();
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        assertThat(messages()).containsExactly("FIELD_BOUGHT_OVER_HEAD");
        assertThat(trust(owner)).anyMatch(e -> e.getReason() == TrustReason.FIELD_BYPASS && e.getDelta() == -8);
        assertThat(publicActions.findAll()).anyMatch(a -> a.getType() == PublicActionType.FIELD_BYPASS);
        ServiceCase sc = claim();
        assertThat(sc.getOfferAmount()).as("10 % of 72,000 €").isEqualTo(7200);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        bypass.pay(sg, sc.getId());
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).filteredOn(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .singleElement().satisfies(o -> assertThat(o.getPayloadJson()).contains("\"amount\":-7200", "COMPENSATION"));
        assertThat(messages()).last().isEqualTo("COMPENSATION_SETTLED");
    }

    @Test
    void refusingOrIgnoringTheClaimCostsMoreTrust() {
        boughtInTheMenu();
        ServiceCase sc = claim();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(8));
        day();
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(sc.getResolution()).isEqualTo("EXPIRED");
        assertThat(trust(owner)).anyMatch(e -> e.getReason() == TrustReason.COMPENSATION_DECLINED && e.getDelta() == -5);
        assertThat(messages()).last().isEqualTo("COMPENSATION_DISPUTE");
    }

    @Test
    void theCooperativeHintsOnceAtHelpersWithoutEmployee() {
        long t = sg.getCurrentGameTime();
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"workforce\": { \"activeJobs\": [{ \"jobId\": 7, \"employeeId\": 3 }, { \"jobId\": 8 }], \"workedGameMs\": {} }");
        var s = fx.snapshot(sg, t, 100_000, doc);
        bypass.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        bypass.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        assertThat(messages()).containsExactly("OUTSIDE_HELPERS_HINT");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getFirst().getFormLink()).isEqualTo("/employees");
    }
}
