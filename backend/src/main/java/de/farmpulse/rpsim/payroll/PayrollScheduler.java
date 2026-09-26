package de.farmpulse.rpsim.payroll;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salaries and installments, triggered by game-time jumps of incoming snapshots - no real-time cron
 * (technical concept "PayrollScheduler"). Credit decisions that became visible are released first.
 * If liquidity is too low for a salary, the payment is not booked and the employee's payFairness drops
 * (system coupling bank/events/employees).
 */
@Component
public class PayrollScheduler {

    private final SavegameRepository savegames;
    private final CreditApplicationService applications;
    private final LoanService loans;
    private final EmployeeRepository employees;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final SatisfactionService satisfaction;
    private final GameTime gameTime;

    public PayrollScheduler(SavegameRepository savegames, CreditApplicationService applications, LoanService loans,
                            EmployeeRepository employees, LiquidityService liquidity, OutboxService outbox,
                            SatisfactionService satisfaction, GameTime gameTime) {
        this.savegames = savegames;
        this.applications = applications;
        this.loans = loans;
        this.employees = employees;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.satisfaction = satisfaction;
        this.gameTime = gameTime;
    }

    @EventListener
    @Order(10)
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        applications.releaseVisibleDecisions(sg);
        loans.processDueInstallments(sg);
        paySalaries(sg);
    }

    /**
     * T-03: the mod could not book a salary (e.g. insufficient funds). The salary stays due and the regular
     * salary-delay logic applies (payFairness malus once per overdue episode).
     */
    @Transactional
    public boolean onSalaryFailed(Long employeeId) {
        Employee emp = employees.findById(employeeId).orElse(null);
        if (emp == null || emp.getStatus() != EmployeeStatus.ACTIVE) {
            return false;
        }
        emp.setNextSalaryDueGameTime(gameTime.addMonths(emp.getSavegame(), emp.getNextSalaryDueGameTime(), -1));
        if (!emp.isSalaryOverdue()) {
            emp.setSalaryOverdue(true);
            satisfaction.salaryOverdue(emp);
        }
        return true;
    }

    /** T-08: "days per period" changed - salary dates keep their month. */
    @EventListener
    @Transactional
    public void onCalendarChanged(CalendarChangedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (Employee emp : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            emp.setNextSalaryDueGameTime(e.remap(emp.getNextSalaryDueGameTime()));
        }
    }

    @Transactional
    public void paySalaries(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Employee emp : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            while (emp.getNextSalaryDueGameTime() <= now) {
                if (liquidity.available(sg) < emp.getMonthlySalary()) {
                    if (!emp.isSalaryOverdue()) {
                        emp.setSalaryOverdue(true);
                        satisfaction.salaryOverdue(emp);
                    }
                    break;
                }
                outbox.money(sg, -emp.getMonthlySalary(), MoneyReason.SALARY_PAYMENT,
                        "Gehalt " + emp.getCharacter().getName(), new Related(SatisfactionService.RELATED, emp.getId()));
                emp.setNextSalaryDueGameTime(gameTime.addMonths(sg, emp.getNextSalaryDueGameTime(), 1));
                emp.setSalaryOverdue(false);
            }
        }
    }
}
