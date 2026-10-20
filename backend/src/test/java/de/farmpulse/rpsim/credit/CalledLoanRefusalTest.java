package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logic review LR-L17 (owner decision 2026-10-20): an installment the mod refuses after the loan was called becomes open
 * remaining debt (DEFAULTED) that the bank collects as soon as liquidity allows.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class CalledLoanRefusalTest {

    @Autowired Fixtures fx;
    @Autowired LoanService loans;
    @Autowired CreditConfigResolver configs;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.bank(sg);
    }

    private List<OutboxInstruction> withReason(String reason) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getPayloadJson().contains(reason)).toList();
    }

    @Test
    void aRefusedInstallmentOfACalledLoanIsCollectedWithTheRemainingDebt() {
        fx.snapshot(sg, 1_000_000);
        Loan l = loans.create(sg, 12_000, 0.0, 12, "Traktor", true, null);
        sg.setCurrentGameTime(l.getNextDueGameTime());
        loans.processDueInstallments(sg);
        assertThat(l.getRemainingAmount()).isEqualTo(11_000);
        loans.callBack(sg, l, configs.forSavegame(sg));
        assertThat(l.getStatus()).isEqualTo(LoanStatus.CALLED);
        assertThat(l.getRemainingAmount()).isZero();

        OutboxInstruction rate = withReason("CREDIT_INSTALLMENT").getFirst();
        rate.setStatus(InstructionStatus.FAILED);
        rate.setAckedAtGameTime(sg.getCurrentGameTime());
        rate.setAckMessage(LiquidityService.INSUFFICIENT_FUNDS);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), rate.getInstructionId(), "FAILED",
                rate.getRelatedEntityType(), rate.getRelatedEntityId()));
        assertThat(l.getStatus()).isEqualTo(LoanStatus.DEFAULTED);
        assertThat(l.getRemainingAmount()).isEqualTo(1_000);

        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(1));
        fx.snapshot(sg, 1_000_000);
        loans.processDueInstallments(sg);
        assertThat(withReason("CREDIT_CALLBACK")).hasSize(2);
        assertThat(l.getStatus()).isEqualTo(LoanStatus.CALLED);
        assertThat(l.getRemainingAmount()).isZero();
    }
}
