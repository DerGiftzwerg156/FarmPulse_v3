package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
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

/** TODO T-20: storm/hail insurance with simulated damages. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class InsuranceServiceTest {

    @Autowired Fixtures fx;
    @Autowired InsuranceService insurance;
    @Autowired ContractBillingService billing;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired CharacterRepository characters;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000); // farmland 12 (54 000, 4.5 ha) + building 120 000 -> insured value 174 000
        var cfg = props.getFormulas().getInsurance();
        cfg.setHailDamagePerHectareMin(800);
        cfg.setHailDamagePerHectareMax(800);
        cfg.setStormDamageShareMin(0.05);
        cfg.setStormDamageShareMax(0.05);
    }

    @AfterEach
    void restore() {
        var d = new RpsimProperties.Insurance();
        var cfg = props.getFormulas().getInsurance();
        cfg.setHailDamagePerHectareMin(d.getHailDamagePerHectareMin());
        cfg.setHailDamagePerHectareMax(d.getHailDamagePerHectareMax());
        cfg.setStormDamageShareMin(d.getStormDamageShareMin());
        cfg.setStormDamageShareMax(d.getStormDamageShareMax());
    }

    private List<String> payloads(String reason) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().map(OutboxInstruction::getPayloadJson)
                .filter(p -> p.contains(reason)).toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    @Test
    void premiumFollowsTheInsuredValue() {
        assertThat(insurance.monthlyPremium(sg, "BASIC")).isEqualTo(50);      // 174 000 × 0.00025 = 43.5 -> minimum 50
        assertThat(insurance.monthlyPremium(sg, "COMFORT")).isEqualTo(131);   // 174 000 × 0.00075 = 130.5
        assertThat(InsuranceService.payout(10_000, 0.6, 2_000)).isEqualTo(4_000);
        assertThat(InsuranceService.payout(3_000, 0.6, 2_000)).isZero();
    }

    @Test
    void offerAcceptAndMonthlyPremium() {
        Contract c = insurance.offer(sg, "COMFORT");
        assertThat(c.getStatus()).isEqualTo(ContractStatus.OFFERED);
        assertThat(characters.findBySavegameOrderByIdAsc(sg)).anyMatch(ch -> ch.getRole() == CharacterRole.INSURANCE_AGENT);
        assertThat(narrations()).contains("CHARACTER_INTRODUCTION", "INSURANCE_OFFER");
        insurance.accept(sg, c.getId());
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(c.getNextDueGameTime()).isEqualTo(sg.getCurrentGameTime() + GameTime.MS_PER_DAY); // next period start
        assertThatThrownBy(() -> insurance.offer(sg, "BASIC")).isInstanceOf(BusinessRuleException.class);
        sg.setCurrentGameTime(c.getNextDueGameTime());
        billing.bill(sg);
        assertThat(payloads("INSURANCE_PREMIUM")).singleElement().satisfies(p -> assertThat(p).contains("-131"));
    }

    @Test
    void insuredHailIsReportedAndPaidOut() {
        Contract c = insurance.accept(sg, insurance.offer(sg, "BASIC").getId());
        ServiceCase sc = insurance.hail(sg).orElseThrow();
        assertThat(sc.getKind()).isEqualTo(CaseKind.HAIL_DAMAGE);
        assertThat(sc.getDamageAmount()).isEqualTo(3_600); // 4.5 ha × 800
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(payloads("\"DAMAGE\"")).singleElement().satisfies(p -> assertThat(p).contains("-3600"));
        insurance.report(sg, sc.getId(), Channel.CALL);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getPayoutAmount()).isEqualTo(160); // round(3 600 × 0.6) − 2 000
        OutboxInstruction payout = outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getPayloadJson().contains("INSURANCE_PAYOUT")).findFirst().orElseThrow();
        assertThat(payout.getGameTimeEarliest()).isGreaterThan(sg.getCurrentGameTime());
        assertThat(narrations()).contains("DAMAGE_NOTICE", "INSURANCE_CLAIM_SETTLED");
        assertThatThrownBy(() -> insurance.report(sg, sc.getId(), Channel.MAIL)).isInstanceOf(BusinessRuleException.class);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void uninsuredStormOnlyCostsMoneyAndTheAgentOffersAgain() {
        ServiceCase sc = insurance.storm(sg).orElseThrow();
        assertThat(sc.getDamageAmount()).isEqualTo(6_000); // 5 % of 120 000
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("UNINSURED");
        assertThat(insurance.active(sg)).isEmpty();
        assertThat(narrations()).contains("DAMAGE_NOTICE", "INSURANCE_OFFER");
    }

    @Test
    void missedReportDeadlineMeansNoPayout() {
        insurance.accept(sg, insurance.offer(sg, "BASIC").getId());
        ServiceCase sc = insurance.hail(sg).orElseThrow();
        sg.setCurrentGameTime(sc.getDeadlineGameTime() + 1);
        insurance.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(sg.getCurrentGameTime()), sg.getCurrentGameTime()));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(narrations()).contains("INSURANCE_CLAIM_REJECTED");
        assertThat(payloads("INSURANCE_PAYOUT")).isEmpty();
    }

    @Test
    void unpaidPremiumsSuspendTheCoverAndEndTheContract() {
        Contract c = insurance.accept(sg, insurance.offer(sg, "BASIC").getId());
        c.setPaymentOverdue(true);
        ServiceCase sc = insurance.hail(sg).orElseThrow();
        assertThat(sc.getResolution()).isEqualTo("COVER_SUSPENDED");
        c.setPaymentOverdue(false);
        insurance.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 1));
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        insurance.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 2));
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("MISSED_PAYMENTS");
        assertThat(narrations()).contains("INSURANCE_PREMIUM_OVERDUE", "INSURANCE_CANCELLED");
    }

    @Test
    void firstOfferComesAfterAFewGameDays() {
        sg.setFirstGameTime(sg.getCurrentGameTime());
        insurance.maybeFirstOffer(sg);
        assertThat(narrations()).doesNotContain("INSURANCE_OFFER");
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(3));
        insurance.maybeFirstOffer(sg);
        insurance.maybeFirstOffer(sg);
        assertThat(narrations().stream().filter("INSURANCE_OFFER"::equals)).hasSize(1);
    }
}
