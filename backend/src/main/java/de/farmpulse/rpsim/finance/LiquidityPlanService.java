package de.farmpulse.rpsim.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.family.FamilyService;
import de.farmpulse.rpsim.finance.FinanceJournalService.Month;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.tax.TaxService;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-K2: liquidity plan over the next FS25 months (Bank app), owner decisions in QUESTIONS.md.
 * <ul>
 *   <li>Known postings, booked at the month start like the tool books them: salaries, loan installments (remaining
 *   term from the loan), contract payments (insurance, maintenance, lease, tax advisor; until a contract ends),
 *   retirement payment, tax prepayments in periods 1, 4, 7 and 10. Until the next year change a prepayment is the
 *   amount of the last assessment; after it an estimate from the current year's tax estimate (same formula as the
 *   billing: estimated tax x prepayment-share / 4), without an estimate the last amount.</li>
 *   <li>Income as a clearly marked estimate: operating result of the same calendar month of the previous year in the
 *   journal, else the average of the complete months; the journal categories the plan lists as known postings are
 *   added back so nothing counts twice.</li>
 *   <li>Balance at the end of a month = previous balance + postings of the month start + income estimate. Warning when
 *   it falls below zero or below the reserve (fixed postings of the month x reserve-factor).</li>
 *   <li>Monthly: the bank advisor writes once when the balance falls below zero within advisor-warning-months (again
 *   only after that month has passed or the plan has recovered).</li>
 * </ul>
 * R3-M2: the expected income of an open forward contract is its own posting in the delivery month, marked as
 * estimate; Roadmap V3.2 R32-G3 the same for an open bulk order with a delivery month. Nothing here books anything; lease income (R3-L) joins once that section exists.
 */
@Service
public class LiquidityPlanService {

    static final List<Integer> PREPAYMENT_PERIODS = List.of(1, 4, 7, 10);

    private final SavegameRepository savegames;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final EmployeeRepository employees;
    private final LoanRepository loans;
    private final ContractRepository contracts;
    private final FamilyService family;
    private final TaxService tax;
    private final GameTime gameTime;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final RpsimProperties props;
    private final de.farmpulse.rpsim.market.ForwardContractService forwardContracts;
    private final de.farmpulse.rpsim.market.BulkOrderService bulkOrders;

    public LiquidityPlanService(SavegameRepository savegames, FactsService facts, FinanceJournalService journal,
                                EmployeeRepository employees, LoanRepository loans, ContractRepository contracts,
                                FamilyService family, TaxService tax, GameTime gameTime, CharacterLookup lookup,
                                NarrationRequestService narration, RpsimProperties props,
                                de.farmpulse.rpsim.market.ForwardContractService forwardContracts,
                                de.farmpulse.rpsim.market.BulkOrderService bulkOrders) {
        this.forwardContracts = forwardContracts;
        this.bulkOrders = bulkOrders;
        this.savegames = savegames;
        this.facts = facts;
        this.journal = journal;
        this.employees = employees;
        this.loans = loans;
        this.contracts = contracts;
        this.family = family;
        this.tax = tax;
        this.gameTime = gameTime;
        this.lookup = lookup;
        this.narration = narration;
        this.props = props;
    }

    private RpsimProperties.LiquidityPlan cfg() {
        return props.getFormulas().getLiquidityPlan();
    }

    /** One known posting of a month start (negative = expense); {@code estimate} = amount not fixed yet. */
    public record Posting(String kind, String label, long amount, boolean estimate) {
    }

    /**
     * One FS25 month: postings at its start, the income estimate ({@code incomeSource} PREVIOUS_YEAR / AVERAGE / NONE),
     * the balance at its end and the warnings.
     */
    public record MonthPlan(long monthIndex, long startGameTime, Integer period, Integer year, List<Posting> postings,
                            long knownTotal, Long incomeEstimate, String incomeSource, long reserve, long balanceEnd,
                            boolean belowZero, boolean belowReserve) {
    }

    public record Plan(boolean available, boolean journalAvailable, long balance, double reserveFactor,
                       List<MonthPlan> months, MonthPlan firstBelowZero, MonthPlan firstBelowReserve) {
    }

    @Transactional(readOnly = true)
    public Plan plan(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.liquidity() == null) {
            return new Plan(false, false, 0, cfg().getReserveFactor(), List.of(), null, null);
        }
        long now = sg.getCurrentGameTime();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long current = anchor.monthIndex(now);
        boolean hasCalendar = f.calendar() != null && f.calendar().year() != null;
        Integer year = hasCalendar ? f.calendar().year() : null;

        List<Month> journalMonths = journal.months(f).stream().filter(Month::complete).toList();
        // salaries: hired employees who start next month are paid on their first working day (owner decision 2026-10-06)
        List<Employee> staff = employees.findBySavegameAndStatusIn(sg,
                java.util.EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.PENDING_START));
        List<Contract> active = contracts.findBySavegameAndStatusOrderByIdAsc(sg, ContractStatus.ACTIVE);
        boolean parents = props.getFormulas().getFamily().isEnabled() && family.members(sg).stream()
                .anyMatch(c -> FamilyService.PARENT.equals(c.getAffiliation()));
        Long retirement = parents ? sg.getRetirementPayment() : null;
        List<LoanSim> loanSims = loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE).stream().map(LoanSim::new).toList();
        List<DueSim> salarySims = staff.stream().map(e -> new DueSim(e.getNextSalaryDueGameTime(), null,
                e.getMonthlySalary())).toList();
        List<ContractSim> contractSims = active.stream().filter(c -> c.getNextDueGameTime() != null && c.getMonthlyAmount() > 0)
                .map(ContractSim::new).toList();

        long balance = Math.round(f.liquidity().balance());
        long running = balance;
        List<MonthPlan> months = new ArrayList<>();
        MonthPlan firstZero = null;
        MonthPlan firstReserve = null;
        Integer y = year;
        for (int k = 1; k <= cfg().getHorizonMonths(); k++) {
            long idx = current + k;
            long start = anchor.monthStart(idx);
            int period = anchor.periodOf(idx);
            if (y != null && period == 1) {
                y = y + 1; // the year changes with period 1 (March)
            }
            List<Posting> postings = new ArrayList<>();
            long salaries = salarySims.stream().mapToLong(s -> s.due(sg, start)).sum();
            if (salaries > 0) {
                postings.add(new Posting("SALARIES", null, -salaries, false));
            }
            for (LoanSim l : loanSims) {
                long paid = l.due(sg, start);
                if (paid > 0) {
                    postings.add(new Posting("LOAN", l.loan.getPurpose(), -paid, false));
                }
            }
            for (ContractSim c : contractSims) {
                if (c.contract.getKind() == ContractKind.LEASE_OUT) {
                    continue; // R3-L1: income, below
                }
                long paid = c.due(sg, start);
                if (paid > 0) {
                    postings.add(new Posting("CONTRACT", c.contract.getKind().name()
                            + (c.contract.getKind() == ContractKind.LEASE && c.contract.getFarmlandId() != null
                            ? ":" + c.contract.getFarmlandId() : ""), -paid, false));
                }
            }
            if (retirement != null && retirement > 0) {
                postings.add(new Posting("RETIREMENT", null, -retirement, false));
            }
            long fixed = -postings.stream().mapToLong(Posting::amount).sum();
            // R3-L1: rent of a leased-out field - known income (owner decision)
            for (ContractSim c : contractSims) {
                long received = c.contract.getKind() == ContractKind.LEASE_OUT ? c.due(sg, start) : 0;
                if (received > 0) {
                    postings.add(new Posting("CONTRACT", ContractKind.LEASE_OUT.name() + ":" + c.contract.getFarmlandId(),
                            received, false));
                }
            }
            // R3-M2: expected income of a forward contract delivered in this month (owner decision: own posting, marked)
            for (var fc : forwardContracts.open(sg)) {
                if (anchor.monthIndex(fc.getDeliveryStartGameTime()) == idx) {
                    postings.add(new Posting("FORWARD_CONTRACT", fc.getFillType(),
                            Math.round(fc.getQuantity() / 1000.0 * fc.getFixedPrice()), true));
                }
            }
            // Roadmap V3.2 R32-G3: expected income of a bulk order delivered in this month (like the forward contract)
            for (var bo : bulkOrders.open(sg)) {
                if (anchor.monthIndex(bo.getDeliveryStartGameTime()) == idx) {
                    postings.add(new Posting("BULK_ORDER", bo.getFillType(),
                            Math.round(bo.getQuantity() / 1000.0 * bo.getFixedPrice()), true));
                }
            }
            if (hasCalendar && PREPAYMENT_PERIODS.contains(period)) {
                boolean afterYearChange = y > year;
                Optional<Long> amount = prepayment(sg, year, afterYearChange);
                amount.filter(a -> a > 0).ifPresent(a -> postings.add(new Posting("TAX_PREPAYMENT", "Q" + ((period - 1) / 3 + 1),
                        -a, afterYearChange && estimatedPrepayment(sg) > 0)));
            }
            long known = postings.stream().mapToLong(Posting::amount).sum();
            Income income = hasCalendar ? income(journalMonths, y, period) : income(journalMonths, null, period);
            running += known + (income.amount() == null ? 0 : income.amount());
            long reserve = Math.round(fixed * cfg().getReserveFactor());
            MonthPlan m = new MonthPlan(idx, start, hasCalendar ? period : null, y, postings, known, income.amount(),
                    income.source(), reserve, running, running < 0, running < reserve);
            months.add(m);
            if (firstZero == null && m.belowZero()) {
                firstZero = m;
            }
            if (firstReserve == null && m.belowReserve()) {
                firstReserve = m;
            }
        }
        return new Plan(true, journal.hasJournal(f), balance, cfg().getReserveFactor(), months, firstZero, firstReserve);
    }

    /** Tax prepayment of a quarter: the last assessment, after the next year change the estimate (if any). */
    Optional<Long> prepayment(Savegame sg, int year, boolean afterYearChange) {
        long last = tax.quarterlyPrepayment(sg, year);
        if (!afterYearChange) {
            return Optional.of(last);
        }
        long estimate = estimatedPrepayment(sg);
        return Optional.of(estimate > 0 ? estimate : last);
    }

    long estimatedPrepayment(Savegame sg) {
        long estimatedTax = tax.overview(sg).estimatedTax();
        return Math.round(estimatedTax * props.getFormulas().getTax().getPrepaymentShare() / 4.0);
    }

    record Income(Long amount, String source) {
    }

    /** Operating result of the month with the known postings added back; previous year, else the average. */
    Income income(List<Month> complete, Integer year, int period) {
        if (complete.isEmpty()) {
            return new Income(null, "NONE");
        }
        if (year != null) {
            for (Month m : complete) {
                if (m.year() == year - 1 && m.period() == period) {
                    return new Income(Math.round(adjusted(m)), "PREVIOUS_YEAR");
                }
            }
        }
        double avg = complete.stream().mapToDouble(this::adjusted).average().orElse(0);
        return new Income(Math.round(avg), "AVERAGE");
    }

    double adjusted(Month m) {
        double v = m.operatingResult();
        for (String category : cfg().getKnownPostingCategories()) {
            v -= m.amountOf(category); // expenses are negative: subtracting adds them back
        }
        return v;
    }

    /** A recurring due date (salary) stepping one month; counts the dues up to a month start once. */
    private final class DueSim {
        private Long next;
        private final Long end;
        private final long amount;

        DueSim(Long next, Long end, long amount) {
            this.next = next;
            this.end = end;
            this.amount = amount;
        }

        long due(Savegame sg, long monthStart) {
            long sum = 0;
            while (next != null && next <= monthStart && (end == null || next <= end)) {
                sum += amount;
                next = gameTime.addMonths(sg, next, 1);
            }
            return sum;
        }
    }

    private final class ContractSim {
        final Contract contract;
        final DueSim dues;

        ContractSim(Contract c) {
            this.contract = c;
            this.dues = new DueSim(c.getNextDueGameTime(), c.getEndsAtGameTime(), c.getMonthlyAmount());
        }

        long due(Savegame sg, long monthStart) {
            return dues.due(sg, monthStart);
        }
    }

    /** Installments like LoanService.pay: interest of the month first, the rest reduces the debt; deferral skipped. */
    private final class LoanSim {
        final Loan loan;
        long remaining;
        long next;

        LoanSim(Loan l) {
            this.loan = l;
            this.remaining = l.getRemainingAmount();
            this.next = l.getDeferredUntilGameTime() == null ? l.getNextDueGameTime()
                    : Math.max(l.getNextDueGameTime(), l.getDeferredUntilGameTime());
        }

        long due(Savegame sg, long monthStart) {
            long sum = 0;
            while (remaining > 0 && next <= monthStart) {
                long interest = Math.round(remaining * loan.getInterestRate() / 12.0);
                long paid = Math.min(loan.getMonthlyInstallment(), remaining + interest);
                remaining -= Math.max(0, paid - interest);
                sum += paid;
                next = gameTime.addMonths(sg, next, 1);
            }
            return sum;
        }
    }

    // ------------------------------------------------------------------------------------------ advisor warning

    /** Month start: the bank advisor writes once when the balance falls below zero within the warning window. */
    @EventListener
    @Order(84)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isAdvisorWarningEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        evaluateWarning(sg);
    }

    void evaluateWarning(Savegame sg) {
        Plan p = plan(sg);
        if (!p.available()) {
            return;
        }
        long current = gameTime.anchor(sg).monthIndex(sg.getCurrentGameTime());
        MonthPlan shortfall = p.months().stream().filter(MonthPlan::belowZero)
                .filter(m -> m.monthIndex() - current <= cfg().getAdvisorWarningMonths()).findFirst().orElse(null);
        if (shortfall == null) {
            sg.setLiquidityWarningMonth(null); // the plan recovered
            return;
        }
        Long warned = sg.getLiquidityWarningMonth();
        if (warned != null && warned >= current) {
            return; // already warned about a shortfall that has not passed yet
        }
        sg.setLiquidityWarningMonth(shortfall.monthIndex());
        narration.request(sg, NarrationEventType.LIQUIDITY_WARNING).from(lookup.bank(sg).orElse(null))
                .facts(NarrationFacts.builder()
                        .put("shortfallMonth", shortfall.period() == null ? null : CalendarText.month(shortfall.period()))
                        .put("monthsAhead", shortfall.monthIndex() - current)
                        .put("projectedBalance", shortfall.balanceEnd())
                        .put("knownPostings", -shortfall.knownTotal()).build())
                .category(CommunicationCategory.CREDIT).formLink("/bank").submit();
    }
}
