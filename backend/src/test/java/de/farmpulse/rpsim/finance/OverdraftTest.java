package de.farmpulse.rpsim.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Owner decisions 2026-10-14: overdraft for every debit of the tool and its monthly interest. */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class OverdraftTest {

    @Autowired Fixtures fx;
    @Autowired LiquidityService liquidity;
    @Autowired OutboxService outbox;
    @Autowired OutboxInstructionRepository instructions;
    @Autowired OverdraftInterestService interest;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
    }

    private List<OutboxInstruction> withReason(MoneyReason reason) {
        return instructions.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getPayloadJson().contains("\"" + reason.name() + "\"")).toList();
    }

    @Test
    void theNewSettingsChangeNothingForExistingSavegames() {
        assertThat(sg.isOverdraftEnabled()).isFalse();
        assertThat(sg.getOverdraftLimit()).isZero();
        assertThat(sg.getOverdraftInterestPercent()).isEqualTo(10.0);
        fx.snapshot(sg, 1_000);
        assertThat(liquidity.available(sg)).isEqualTo(1_000);
        assertThat(LiquidityService.minBalance(sg)).isZero();
    }

    @Test
    void theOverdraftRaisesTheAvailableMoneyByTheLimit() {
        fx.snapshot(sg, 1_000);
        sg.setOverdraftEnabled(true);
        sg.setOverdraftLimit(5_000);
        assertThat(liquidity.available(sg)).isEqualTo(6_000);
        assertThat(LiquidityService.minBalance(sg)).isEqualTo(-5_000);
        outbox.money(sg, -4_000, MoneyReason.TAX_PAYMENT, "Steuer", Related.none());
        assertThat(liquidity.available(sg)).isEqualTo(2_000);
    }

    @Test
    void everyDebitCarriesTheMinimumBalanceButCreditsDoNot() {
        sg.setOverdraftEnabled(true);
        sg.setOverdraftLimit(5_000);
        OutboxInstruction debit = outbox.money(sg, -100, MoneyReason.COOP_SHARES, "Anteile", Related.none());
        OutboxInstruction credit = outbox.money(sg, 100, MoneyReason.GOODS_SALE, "Verkauf", Related.none());
        OutboxInstruction vehicle = outbox.vehicleSpawn(sg, "a.xml", 1, 1, 0, 0, 900, Related.none());
        assertThat(debit.getPayloadJson()).contains("\"minBalance\":-5000");
        assertThat(credit.getPayloadJson()).doesNotContain("minBalance");
        assertThat(vehicle.getPayloadJson()).contains("\"minBalance\":-5000");
    }

    @Test
    void aRefusedDebitBlocksTheOverdraftUntilANewSnapshot() {
        fx.snapshot(sg, 0);
        sg.setOverdraftEnabled(true);
        sg.setOverdraftLimit(5_000);
        OutboxInstruction ins = outbox.money(sg, -4_000, MoneyReason.TAX_PAYMENT, "Steuer", Related.none());
        ins.setStatus(InstructionStatus.FAILED);
        ins.setAckedAtGameTime(sg.getCurrentGameTime());
        ins.setAckMessage(LiquidityService.INSUFFICIENT_FUNDS);
        assertThat(liquidity.available(sg)).isLessThanOrEqualTo(0);
    }

    @Test
    void interestIsChargedAtTheMonthStartOnANegativeBalance() {
        sg.setOverdraftEnabled(true);
        sg.setOverdraftLimit(20_000);
        sg.setOverdraftInterestPercent(12);
        fx.snapshot(sg, -10_000);
        interest.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(withReason(MoneyReason.OVERDRAFT_INTEREST)).singleElement().satisfies(o -> {
            assertThat(liquidity.amount(o)).isEqualTo(-100); // 10,000 x 12 % / 12
            assertThat(o.getPayloadJson()).contains("\"ignoreFunds\":true").doesNotContain("minBalance");
        });
    }

    @Test
    void noInterestOnAPositiveBalanceOrWithTheOverdraftOff() {
        fx.snapshot(sg, -10_000);
        assertThat(interest.interest(sg)).isZero(); // overdraft off
        sg.setOverdraftEnabled(true);
        sg.setOverdraftInterestPercent(0);
        assertThat(interest.interest(sg)).isZero(); // rate 0
        sg.setOverdraftInterestPercent(10);
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 500,
                de.farmpulse.rpsim.support.TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime() + 1, 500));
        interest.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(withReason(MoneyReason.OVERDRAFT_INTEREST)).isEmpty();
    }
}
