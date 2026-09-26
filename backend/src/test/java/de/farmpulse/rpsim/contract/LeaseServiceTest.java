package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-22: lease of NPC fields. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class LeaseServiceTest {

    @Autowired Fixtures fx;
    @Autowired LeaseService lease;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired NegotiationEngine negotiations;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustScoreService trust;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character owner;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        owner = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        owner.setSellWilling(true);
        fx.snapshot(sg, 100_000); // player owns farmland 12
        ownership.reconcile(sg);
        ownership.setOwner(sg, 13, OwnerType.CHARACTER, owner); // 6 ha, reference price 72 000
        props.getFormulas().getLease().setAcceptProbability(1);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setLease(new RpsimProperties.Lease());
    }

    private List<OutboxInstruction> transfers() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.FARMLAND_TRANSFER)
                .toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private Contract activeLease() {
        Contract c = lease.requestOffer(sg, 13);
        return lease.accept(sg, c.getId());
    }

    @Test
    void rentFollowsReferencePriceAndTrust() {
        RpsimProperties.Lease cfg = new RpsimProperties.Lease();
        assertThat(LeaseService.monthlyRent(72_000, 0, cfg)).isEqualTo(300); // 72 000 × 5 % / 12
        assertThat(LeaseService.monthlyRent(72_000, 100, cfg)).isEqualTo(270);
        assertThat(LeaseService.monthlyRent(72_000, -100, cfg)).isEqualTo(330);
    }

    @Test
    void offerAcceptTransfersTheFieldButTheToolKeepsTheOwner() {
        Contract c = lease.requestOffer(sg, 13);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.OFFERED);
        assertThat(c.getMonthlyAmount()).isEqualTo(LeaseService.monthlyRent(72_000, trust.getCurrentTrust(owner),
                props.getFormulas().getLease()));
        assertThat(narrations()).contains("LEASE_OFFER");
        assertThatThrownBy(() -> lease.requestOffer(sg, 13)).isInstanceOf(BusinessRuleException.class);
        lease.accept(sg, c.getId());
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(c.getEndsAtGameTime()).isNotNull();
        assertThat(transfers()).singleElement().satisfies(o -> {
            assertThat(o.getPayloadJson()).contains("\"TO_PLAYER\"").contains("\"farmlandId\":13");
            assertThat(o.getRelatedEntityType()).isEqualTo(LeaseService.RELATED);
        });
        // the next export shows field 13 as the player's - the tool keeps the owner
        long t = sg.getCurrentGameTime() + 1;
        sg.setCurrentGameTime(t);
        outbox.findBySavegameOrderByIdAsc(sg).forEach(o -> o.setStatus(de.farmpulse.rpsim.domain.InstructionStatus.APPLIED));
        fx.snapshot(sg, t, 100_000, TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000).replace(
                "\"farmland\": [{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]",
                "\"farmland\": [{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 },"
                        + "{ \"farmlandId\": 13, \"hectares\": 6, \"price\": 72000 }]"));
        ownership.reconcile(sg);
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.CHARACTER);
        assertThat(ownership.get(sg, 13).orElseThrow().isLeasedToPlayer()).isTrue();
        // leased fields are neither negotiated nor leased twice
        assertThatThrownBy(() -> negotiations.startDirect(sg, owner.getId(), 13)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> lease.requestOffer(sg, 13)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void theOwnerMayRefuse() {
        props.getFormulas().getLease().setAcceptProbability(0);
        props.getFormulas().getLease().setAcceptTrustInfluence(0);
        Contract c = lease.requestOffer(sg, 13);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(c.getEndReason()).isEqualTo("OWNER_REFUSED");
        assertThat(narrations()).contains("LEASE_REFUSED");
    }

    @Test
    void onlyNpcFieldsCanBeLeased() {
        assertThatThrownBy(() -> lease.requestOffer(sg, 12)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void warningBeforeTheEndThenAutomaticReturn() {
        Contract c = activeLease();
        long end = c.getEndsAtGameTime();
        sg.setCurrentGameTime(end - GameTime.hours(1));
        lease.check(sg);
        assertThat(c.isRenewalOffered()).isTrue();
        assertThat(c.getRenewalAmount()).isBetween(Math.round(c.getMonthlyAmount() * 0.9), Math.round(c.getMonthlyAmount() * 1.2));
        assertThat(c.getPurchasePrice()).isEqualTo(75_600); // 72 000 × 1.05
        assertThat(narrations()).contains("LEASE_ENDING");
        sg.setCurrentGameTime(end);
        lease.check(sg);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("TERM_ENDED");
        assertThat(transfers()).hasSize(2).last().satisfies(o -> assertThat(o.getPayloadJson()).contains("\"FROM_PLAYER\""));
        assertThat(ownership.get(sg, 13).orElseThrow().isLeasedToPlayer()).isFalse();
        assertThat(narrations()).contains("LEASE_ENDED");
    }

    @Test
    void renewalExtendsTheTermAtTheNewRent() {
        Contract c = activeLease();
        long end = c.getEndsAtGameTime();
        sg.setCurrentGameTime(end - GameTime.hours(1));
        lease.check(sg);
        long newRent = c.getRenewalAmount();
        lease.renew(sg, c.getId());
        assertThat(c.getMonthlyAmount()).isEqualTo(newRent);
        assertThat(c.getEndsAtGameTime()).isGreaterThan(end);
        assertThat(c.isRenewalOffered()).isFalse();
        sg.setCurrentGameTime(end + 1);
        lease.check(sg);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void purchaseMakesTheFieldThePlayersAndARefusedBookingRevertsIt() {
        Contract c = activeLease();
        sg.setCurrentGameTime(c.getEndsAtGameTime() - GameTime.hours(1));
        lease.check(sg);
        lease.buy(sg, c.getId());
        assertThat(c.getEndReason()).isEqualTo("PURCHASED");
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.PLAYER);
        OutboxInstruction money = outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getPayloadJson().contains("FARMLAND_PURCHASE")).findFirst().orElseThrow();
        assertThat(money.getPayloadJson()).contains("-75600");
        assertThat(lease.onInstructionFailed(c.getId(), InstructionType.MONEY_TRANSACTION, false, "FARMLAND_PURCHASE")).isTrue();
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(ownership.get(sg, 13).orElseThrow().getOwnerType()).isEqualTo(OwnerType.CHARACTER);
        assertThat(ownership.get(sg, 13).orElseThrow().isLeasedToPlayer()).isTrue();
    }

    @Test
    void unpaidRentEndsTheLeaseEarly() {
        Contract c = activeLease();
        lease.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 1));
        assertThat(narrations()).contains("LEASE_RENT_OVERDUE");
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        lease.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 2));
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("RENT_MISSED");
    }

    @Test
    void failedStartTransferVoidsTheLease() {
        Contract c = activeLease();
        assertThat(lease.onInstructionFailed(c.getId(), InstructionType.FARMLAND_TRANSFER, true, "")).isTrue();
        assertThat(c.getEndReason()).isEqualTo("TRANSFER_FAILED");
        assertThat(ownership.get(sg, 13).orElseThrow().isLeasedToPlayer()).isFalse();
    }
}
