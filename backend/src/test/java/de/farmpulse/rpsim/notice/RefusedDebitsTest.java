package de.farmpulse.rpsim.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bypass.VanillaBypassService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.DamageDebitService;
import de.farmpulse.rpsim.cooperative.CooperativeService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.villagelife.CropDamageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Logic review LR-M10 (owner decisions 2026-10-20): debits the mod refused for lack of money get a follow-up. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class RefusedDebitsTest {

    @Autowired Fixtures fx;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired OutboxService outboxService;
    @Autowired ServiceCaseRepository cases;
    @Autowired VanillaBypassService bypass;
    @Autowired CropDamageService cropDamage;
    @Autowired CooperativeService cooperative;
    @Autowired DamageDebitService damages;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character neighbour;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        neighbour = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Hans Wieland");
        balance(100_000);
    }

    private void balance(long balance) {
        long t = sg.getCurrentGameTime() + 1;
        fx.snapshot(sg, t, balance, TestData.farmFacts(sg.getBridgeSavegameId(), t, balance));
    }

    /** The ack path of BridgeSyncService for a debit the mod refused. */
    private void refuse(OutboxInstruction ins) {
        ins.setStatus(InstructionStatus.FAILED);
        ins.setAckedAtGameTime(sg.getCurrentGameTime());
        ins.setAckMessage(LiquidityService.INSUFFICIENT_FUNDS);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), "FAILED",
                ins.getRelatedEntityType(), ins.getRelatedEntityId()));
    }

    private OutboxInstruction last(String reason) {
        List<OutboxInstruction> all = outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getPayloadJson().contains("\"" + reason + "\"")).toList();
        return all.getLast();
    }

    private ServiceCase claim(CaseKind kind, long amount) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(neighbour);
        sc.setFarmlandId(7);
        sc.setOfferAmount(amount);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(1));
        sc.setCreatedAt(Instant.now());
        return cases.save(sc);
    }

    @Test
    void aRefusedBypassCompensationIsOpenAgainWithANewDecisionTime() {
        ServiceCase sc = claim(CaseKind.COMPENSATION_CLAIM, 5_000);
        bypass.pay(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(3)); // after the first deadline
        refuse(last("COMPENSATION"));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getResolution()).isNull();
        assertThat(sc.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime()
                + GameTime.days(props.getFormulas().getVanillaBypass().getCompensationDecisionDays()));
        balance(1_000);
        assertThatThrownBy(() -> bypass.pay(sg, sc.getId())).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Kontostand");
    }

    @Test
    void aRefusedCropDamageCompensationIsOpenAgain() {
        ServiceCase sc = claim(CaseKind.CROP_DAMAGE_CLAIM, 800);
        cropDamage.pay(sg, sc.getId());
        refuse(last("COMPENSATION"));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime()
                + GameTime.days(props.getFormulas().getCropDamage().getDecisionDays()));
        balance(100);
        assertThatThrownBy(() -> cropDamage.pay(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void refusedSharesAreTakenBack() {
        cooperative.buy(sg, 4);
        assertThat(sg.getCoopShares()).isEqualTo(4);
        refuse(last("COOP_SHARES"));
        assertThat(sg.getCoopShares()).isZero();
    }

    @Test
    void aRefusedDamageIsBookedAgainWhenTheMoneyCoversIt() {
        OutboxInstruction damage = outboxService.money(sg, -3_000, MoneyReason.DAMAGE, "Hagelschaden Feld 7",
                new OutboxService.Related("SERVICE_CASE", 77L));
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(1)); // refused after the latest snapshot
        refuse(damage);
        assertThat(damages.rebook(sg)).as("blocked until a newer snapshot (T-03)").isZero();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(1));
        balance(2_000);
        assertThat(damages.rebook(sg)).as("not covered yet").isZero();
        balance(50_000);
        assertThat(damages.rebook(sg)).isEqualTo(1);
        OutboxInstruction again = last("DAMAGE");
        assertThat(again.getId()).isNotEqualTo(damage.getId());
        assertThat(again.getStatus()).isEqualTo(InstructionStatus.PENDING);
        assertThat(again.getRelatedEntityId()).isEqualTo(77L);
        assertThat(again.getPayloadJson()).contains("\"amount\":-3000", "Hagelschaden Feld 7");
        assertThat(damages.rebook(sg)).as("the new booking is pending").isZero();
    }
}
