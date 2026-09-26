package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-20: hunter and wildlife damage. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class HuntingServiceTest {

    @Autowired Fixtures fx;
    @Autowired HuntingService hunting;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 100_000); // own farmland 12 with 4.5 ha
        props.getFormulas().getHunting().setDamagePerHectareMin(400);
        props.getFormulas().getHunting().setDamagePerHectareMax(400);
    }

    @AfterEach
    void restore() {
        var d = new RpsimProperties.Hunting();
        props.getFormulas().getHunting().setDamagePerHectareMin(d.getDamagePerHectareMin());
        props.getFormulas().getHunting().setDamagePerHectareMax(d.getDamagePerHectareMax());
    }

    private List<String> payloads(String reason) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().map(OutboxInstruction::getPayloadJson)
                .filter(p -> p.contains(reason)).toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void shareDependsOnTrust() {
        assertThat(HuntingService.share(0.5, 0, 0.2)).isCloseTo(0.5, within(1e-9));
        assertThat(HuntingService.share(0.5, 100, 0.2)).isCloseTo(0.7, within(1e-9));
        assertThat(HuntingService.share(0.9, 100, 0.2)).isEqualTo(1.0);
        assertThat(HuntingService.share(0.1, -100, 0.2)).isEqualTo(0.1);
    }

    @Test
    void damageCostsMoneyAndTheHunterOffersHalf() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        assertThat(sc.getDamageAmount()).isEqualTo(1_800);
        assertThat(sc.getOfferAmount()).isEqualTo(900);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(payloads("\"DAMAGE\"")).singleElement().satisfies(p -> assertThat(p).contains("-1800"));
        assertThat(narrations()).contains("CHARACTER_INTRODUCTION", "WILDLIFE_DAMAGE_REPORTED");
        hunting.accept(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(payloads("WILDLIFE_COMPENSATION")).singleElement().satisfies(p -> assertThat(p).contains("900"));
    }

    @Test
    void counterDemandsUpToTheLimitAreAcceptedHigherOnesGetACounterOffer() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        assertThatThrownBy(() -> hunting.counter(sg, sc.getId(), 5_000)).isInstanceOf(BusinessRuleException.class);
        hunting.counter(sg, sc.getId(), 1_700); // above the limit 1 620 (90 %)
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getOfferAmount()).isEqualTo(1_260); // (900 + 1 620) / 2
        assertThat(narrations()).contains("WILDLIFE_COUNTER");
        hunting.counter(sg, sc.getId(), 1_500);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getPayoutAmount()).isEqualTo(1_500);
    }

    @Test
    void afterTheLastRoundTheOfferIsFinal() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        hunting.counter(sg, sc.getId(), 1_800);
        hunting.counter(sg, sc.getId(), 1_800);
        assertThatThrownBy(() -> hunting.counter(sg, sc.getId(), 1_700)).isInstanceOf(BusinessRuleException.class);
        hunting.accept(sg, sc.getId());
        assertThat(sc.getPayoutAmount()).isEqualTo(sc.getOfferAmount());
    }

    @Test
    void jointMeasureCostsAContributionAndImprovesReputationAndLowersTheRisk() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        assertThat(hunting.measureActive(sg)).isFalse();
        hunting.measure(sg, sc.getId());
        assertThat(sc.isMeasureAgreed()).isTrue();
        assertThat(payloads("Wildschutzmaßnahme")).singleElement().satisfies(p -> assertThat(p).contains("-400"));
        assertThat(publicActions.findAll()).anyMatch(a -> a.getType() == PublicActionType.WILDLIFE_MEASURE);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(sc.getCharacter())).extracting(e -> e.getReason())
                .contains(TrustReason.WILDLIFE_AGREEMENT);
        assertThat(hunting.measureActive(sg)).isTrue();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(7));
        assertThat(hunting.measureActive(sg)).isFalse(); // fallback calendar: 6 months = 6 days
    }

    @Test
    void refusalIsAPublicDispute() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        hunting.decline(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(payloads("WILDLIFE_COMPENSATION")).isEmpty();
        assertThat(publicActions.findAll()).anyMatch(a -> a.getType() == PublicActionType.WILDLIFE_DISPUTE);
        assertThat(narrations()).contains("WILDLIFE_DISPUTE");
    }

    @Test
    void withoutAnswerTheLastOfferIsPaid() {
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        sg.setCurrentGameTime(sc.getDeadlineGameTime() + 1);
        hunting.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(sg.getCurrentGameTime()), sg.getCurrentGameTime()));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("OFFER_PAID_BY_DEFAULT");
        assertThat(payloads("WILDLIFE_COMPENSATION")).hasSize(1);
    }
}
