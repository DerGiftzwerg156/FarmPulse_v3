package de.farmpulse.rpsim.payroll;

import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Salaries and installments, triggered by game-time jumps of incoming snapshots - no real-time cron
 * (technical concept "PayrollScheduler"). Credit decisions that became visible are released first.
 */
@Component
public class PayrollScheduler {

    private final SavegameRepository savegames;
    private final CreditApplicationService applications;
    private final LoanService loans;

    public PayrollScheduler(SavegameRepository savegames, CreditApplicationService applications, LoanService loans) {
        this.savegames = savegames;
        this.applications = applications;
        this.loans = loans;
    }

    @EventListener
    @Order(10)
    public void onGameTime(GameTimeAdvancedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        applications.releaseVisibleDecisions(sg);
        loans.processDueInstallments(sg);
    }
}
