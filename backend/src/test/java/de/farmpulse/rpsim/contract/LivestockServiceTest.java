package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-20: vet, livestock trader and breeding advisor. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class LivestockServiceTest {

    @Autowired Fixtures fx;
    @Autowired LivestockService livestock;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired de.farmpulse.rpsim.bridge.FactsService factsService;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 100_000); // 24 cows worth 96 000 -> 4 000 per animal
        RpsimProperties.Livestock c = props.getFormulas().getLivestock();
        c.setTraderProbabilityPerMonth(0);
        c.setTraderBuyShare(0);
        c.setTraderQuantityMin(3);
        c.setTraderQuantityMax(3);
        c.setTraderPremiumShareMin(0.1);
        c.setTraderPremiumShareMax(0.1);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setLivestock(new RpsimProperties.Livestock());
    }

    private List<String> payloads(String reason) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().map(OutboxInstruction::getPayloadJson)
                .filter(p -> p.contains(reason)).toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    /** New export with a different cow count, later than every earlier snapshot. */
    private void cows(int count) {
        long t = sg.getCurrentGameTime() + 1;
        sg.setCurrentGameTime(t);
        fx.snapshot(sg, t, 100_000, TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000)
                .replace("\"count\": 24", "\"count\": " + count));
    }

    private ServiceCase sellOffer() {
        return livestock.traderOffer(sg, LivestockService.herds(fxFacts()).get("COW")).orElseThrow();
    }

    private de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts fxFacts() {
        return factsService.latest(sg).orElseThrow();
    }

    @Test
    void herdsAreSummedPerType() {
        var herds = LivestockService.herds(fxFacts());
        assertThat(herds).containsOnlyKeys("COW");
        assertThat(herds.get("COW").count()).isEqualTo(24);
        assertThat(herds.get("COW").unitValue()).isEqualTo(4_000);
        assertThat(LivestockService.herds((de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts) null)).isEmpty();
    }

    @Test
    void monthlyVetVisitAndBreedingAdviceOnlyWhenDue() {
        livestock.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(payloads("VET_INVOICE")).singleElement().satisfies(p -> assertThat(p).contains("-176")); // 80 + 4 x 24
        assertThat(narrations()).contains("VET_VISIT", "BREEDING_ADVICE");
        long visits = cases.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == CaseKind.VET_VISIT).count();
        livestock.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(cases.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == CaseKind.VET_VISIT).count())
                .isEqualTo(visits);
    }

    @Test
    void noAnimalsNoServiceCases() {
        cows(0);
        livestock.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(cases.findBySavegameOrderByIdDesc(sg)).isEmpty();
    }

    @Test
    void acceptedSellOfferPaysThePremiumOnceTheHerdShrank() {
        ServiceCase sc = sellOffer();
        assertThat(sc.getDirection()).isEqualTo("SELL");
        assertThat(sc.getQuantity()).isEqualTo(3);
        assertThat(sc.getOfferAmount()).isEqualTo(400);
        assertThat(narrations()).contains("LIVESTOCK_OFFER");
        livestock.accept(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(sc.getBaselineCount()).isEqualTo(24);
        livestock.check(sg);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        cows(20);
        livestock.check(sg);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("FULFILLED");
        assertThat(sc.getPayoutAmount()).isEqualTo(1_200); // capped at the agreed 3 animals
        assertThat(payloads("LIVESTOCK_PREMIUM")).singleElement().satisfies(p -> assertThat(p).contains("1200"));
        assertThat(narrations()).contains("LIVESTOCK_PREMIUM_PAID");
    }

    @Test
    void partialDeliveryAtTheDeadlinePaysPerAnimalMoved() {
        ServiceCase sc = sellOffer();
        livestock.accept(sg, sc.getId());
        cows(23);
        sg.setCurrentGameTime(sc.getDeadlineGameTime() + GameTime.days(1));
        livestock.check(sg);
        assertThat(sc.getResolution()).isEqualTo("PARTIAL");
        assertThat(sc.getPayoutAmount()).isEqualTo(400);
    }

    @Test
    void nothingMovedLapsesWithoutMoney() {
        ServiceCase sc = sellOffer();
        livestock.accept(sg, sc.getId());
        sg.setCurrentGameTime(sc.getDeadlineGameTime() + GameTime.days(1));
        livestock.check(sg);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("LAPSED");
        assertThat(payloads("LIVESTOCK_PREMIUM")).isEmpty();
        assertThat(narrations()).contains("LIVESTOCK_OFFER_LAPSED");
    }

    @Test
    void buyOfferCountsGrowth() {
        props.getFormulas().getLivestock().setTraderBuyShare(1);
        ServiceCase sc = sellOffer();
        assertThat(sc.getDirection()).isEqualTo("BUY");
        livestock.accept(sg, sc.getId());
        cows(30);
        livestock.check(sg);
        assertThat(sc.getResolution()).isEqualTo("FULFILLED");
    }

    @Test
    void unansweredOffersExpireAndClosedOffersCannotBeAccepted() {
        ServiceCase sc = sellOffer();
        sg.setCurrentGameTime(sc.getDeadlineGameTime() + 1);
        livestock.check(sg);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("NO_ANSWER");
        assertThatThrownBy(() -> livestock.accept(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);
        ServiceCase other = sellOffer();
        livestock.decline(sg, other.getId());
        assertThat(other.getStatus()).isEqualTo(CaseStatus.DECLINED);
    }

    @Test
    void sellOffersNeedAHerdLargeEnough() {
        cows(5); // 30 % of 5 = 1 < min 3
        assertThat(livestock.traderOffer(sg, LivestockService.herds(fxFacts()).get("COW"))).isEmpty();
    }
}
