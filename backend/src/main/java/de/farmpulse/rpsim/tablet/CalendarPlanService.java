package de.farmpulse.rpsim.tablet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.farmpulse.rpsim.api.Views.AgendaEntryView;
import de.farmpulse.rpsim.api.Views.CalendarOverviewView;
import de.farmpulse.rpsim.api.Views.DebitView;
import de.farmpulse.rpsim.api.Views.YearEventView;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.family.FamilyService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.tax.TaxService;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hof-Tablet app "Kalender": the scheduled dates the tool already knows, put in one place - what is booked at the next
 * month start (salaries, loan instalments, contract payments, retirement payment), the dated entries of the next game
 * days and the fixed dates of the FS25 year (festivals, tax dates, family occasions, the rotation check). Deadlines of
 * open decisions come from {@link TaskService}; nothing here books or decides anything.
 */
@Service
public class CalendarPlanService {

    /** Display window of the agenda (game days ahead); not a formula value. */
    static final int AGENDA_DAYS = 7;
    /** FS25 periods that start a tax quarter (TaxService: prepayments at the start of periods 1, 4, 7, 10). */
    static final List<Integer> PREPAYMENT_PERIODS = List.of(1, 4, 7, 10);

    private final EmployeeRepository employees;
    private final LoanRepository loans;
    private final ContractRepository contracts;
    private final FamilyService family;
    private final TaxService tax;
    private final FactsService facts;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final de.farmpulse.rpsim.market.BulkOrderService bulkOrders;

    public CalendarPlanService(EmployeeRepository employees, LoanRepository loans, ContractRepository contracts,
                               FamilyService family, TaxService tax, FactsService facts, GameTime gameTime,
                               RpsimProperties props, de.farmpulse.rpsim.market.BulkOrderService bulkOrders) {
        this.bulkOrders = bulkOrders;
        this.employees = employees;
        this.loans = loans;
        this.contracts = contracts;
        this.family = family;
        this.tax = tax;
        this.facts = facts;
        this.gameTime = gameTime;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public CalendarOverviewView overview(Savegame sg) {
        long now = sg.getCurrentGameTime();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long monthIndex = anchor.monthIndex(now);
        long nextStart = anchor.monthStart(monthIndex + 1);
        var calendar = facts.latest(sg).map(f -> f.calendar()).orElse(null);
        Integer period = calendar == null ? null : anchor.periodOf(monthIndex);
        Integer nextPeriod = calendar == null ? null : anchor.periodOf(monthIndex + 1);
        Integer year = calendar == null ? null : calendar.year();

        // salaries: hired employees who start next month are paid on their first working day (owner decision 2026-10-06)
        List<Employee> staff = employees.findBySavegameAndStatusIn(sg,
                java.util.EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.PENDING_START));
        List<Loan> activeLoans = loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE);
        List<Contract> activeContracts = contracts.findBySavegameAndStatusOrderByIdAsc(sg, ContractStatus.ACTIVE);
        List<Character> members = props.getFormulas().getFamily().isEnabled() ? family.members(sg) : List.of();
        boolean parents = members.stream().anyMatch(c -> FamilyService.PARENT.equals(c.getAffiliation()));

        List<DebitView> debits = debits(staff, activeLoans, activeContracts, parents ? sg.getRetirementPayment() : null,
                nextStart);
        long total = debits.stream().mapToLong(DebitView::amount).sum();
        List<AgendaEntryView> agenda = agenda(sg, anchor, now, staff, activeLoans, activeContracts, calendar != null);
        List<YearEventView> yearEvents = calendar == null ? List.of() : yearEvents(sg, members, year);
        return new CalendarOverviewView(now, period, year, anchor.daysPerPeriod(), nextStart, nextPeriod, agenda, debits,
                total, yearEvents);
    }

    /** Everything due at or before the next month start (salaries and payments are booked at a month start). */
    List<DebitView> debits(List<Employee> staff, List<Loan> activeLoans, List<Contract> activeContracts, Long retirement,
                           long nextStart) {
        List<DebitView> out = new ArrayList<>();
        List<Employee> paid = staff.stream().filter(e -> e.getNextSalaryDueGameTime() <= nextStart).toList();
        if (!paid.isEmpty()) {
            out.add(new DebitView("SALARIES", null, null, paid.stream().mapToLong(Employee::getMonthlySalary).sum(),
                    paid.size()));
        }
        for (Loan l : activeLoans) {
            boolean deferred = l.getDeferredUntilGameTime() != null && l.getDeferredUntilGameTime() >= l.getNextDueGameTime();
            if (l.getNextDueGameTime() <= nextStart && !deferred) {
                out.add(new DebitView("LOAN", null, l.getPurpose(), Math.min(l.getMonthlyInstallment(), l.getRemainingAmount()), 1));
            }
        }
        for (Contract c : activeContracts) {
            if (c.getNextDueGameTime() != null && c.getNextDueGameTime() <= nextStart && c.getMonthlyAmount() > 0
                    && c.getKind() != ContractKind.LEASE_OUT) { // R3-L1: income, no debit
                out.add(new DebitView("CONTRACT", c.getKind().name(),
                        c.getKind() == ContractKind.LEASE && c.getFarmlandId() != null ? String.valueOf(c.getFarmlandId())
                                : c.getLevel(), c.getMonthlyAmount(), 1));
            }
        }
        if (retirement != null && retirement > 0) {
            out.add(new DebitView("RETIREMENT", null, null, retirement, 1));
        }
        return out;
    }

    List<AgendaEntryView> agenda(Savegame sg, GameTime.Anchor anchor, long now, List<Employee> staff,
                                 List<Loan> activeLoans, List<Contract> activeContracts, boolean hasCalendar) {
        long until = now + GameTime.days(AGENDA_DAYS);
        List<AgendaEntryView> out = new ArrayList<>();
        for (long idx = anchor.monthIndex(now) + 1; anchor.monthStart(idx) <= until; idx++) {
            long start = anchor.monthStart(idx);
            Integer period = hasCalendar ? anchor.periodOf(idx) : null;
            out.add(new AgendaEntryView(start, "MONTH_START", null, null, null, period == null ? null : String.valueOf(period)));
            if (period == null) {
                continue;
            }
            for (RpsimProperties.Festival f : props.getFormulas().getClubs().getFestivals()) {
                if (props.getFormulas().getClubs().isEnabled() && f.getPeriod() == period) {
                    out.add(new AgendaEntryView(start, "FESTIVAL", f.getHost(), null, null, f.getKey()));
                }
            }
            if (PREPAYMENT_PERIODS.contains(period)) {
                out.add(new AgendaEntryView(start, period == 1 ? "TAX_ASSESSMENT" : "TAX_PREPAYMENT", null, null, null, null));
            }
            // Roadmap V3.1 R31-D7: general assembly of the members, board meetings
            if (sg.getCoopShares() > 0 && period == props.getFormulas().getCoopAssembly().getPeriod()) {
                out.add(new AgendaEntryView(start, "COOP_ASSEMBLY", null, null, null, null));
            }
            if (sg.isCoopBoard() && props.getFormulas().getCoopBoard().getMeetingPeriods().contains(period)) {
                out.add(new AgendaEntryView(start, "COOP_BOARD_MEETING", null, null, null, null));
            }
        }
        // Roadmap V3.1 R31-D3: the next regulars' table invitation
        Long stammtisch = sg.getStammtischNextGameTime();
        if (props.getFormulas().getStammtisch().isEnabled() && stammtisch != null && stammtisch > now && stammtisch <= until) {
            out.add(new AgendaEntryView(stammtisch, "STAMMTISCH", null, null, null, null));
        }
        for (Loan l : activeLoans) {
            if (l.getNextDueGameTime() > now && l.getNextDueGameTime() <= until) {
                out.add(new AgendaEntryView(l.getNextDueGameTime(), "LOAN_INSTALLMENT", null, l.getPurpose(),
                        Math.min(l.getMonthlyInstallment(), l.getRemainingAmount()), null));
            }
        }
        long salaries = staff.stream().filter(e -> e.getNextSalaryDueGameTime() > now && e.getNextSalaryDueGameTime() <= until)
                .mapToLong(Employee::getMonthlySalary).sum();
        staff.stream().filter(e -> e.getNextSalaryDueGameTime() > now && e.getNextSalaryDueGameTime() <= until)
                .mapToLong(Employee::getNextSalaryDueGameTime).min()
                .ifPresent(t -> out.add(new AgendaEntryView(t, "SALARIES", null, null, salaries, null)));
        for (Contract c : activeContracts) {
            if (c.getNextDueGameTime() != null && c.getNextDueGameTime() > now && c.getNextDueGameTime() <= until
                    && c.getMonthlyAmount() > 0 && c.getKind() != ContractKind.LEASE_OUT) {
                out.add(new AgendaEntryView(c.getNextDueGameTime(), "CONTRACT_PAYMENT", c.getKind().name(), null,
                        c.getMonthlyAmount(), c.getFarmlandId() == null ? null : String.valueOf(c.getFarmlandId())));
            }
            if (c.getKind() == ContractKind.LEASE && c.getEndsAtGameTime() != null && c.getEndsAtGameTime() > now
                    && c.getEndsAtGameTime() <= until) {
                out.add(new AgendaEntryView(c.getEndsAtGameTime(), "LEASE_END", null, null, null,
                        c.getFarmlandId() == null ? null : String.valueOf(c.getFarmlandId())));
            }
        }
        // Roadmap V3.2 R32-G3: delivery month of a bulk order - start and end (title = sell point, reference = fill type)
        for (var o : bulkOrders.open(sg)) {
            String where = bulkOrders.sellPointName(sg, o.getSellPoint());
            long income = Math.round(o.getQuantity() / 1000.0 * o.getFixedPrice());
            if (o.getDeliveryStartGameTime() > now && o.getDeliveryStartGameTime() <= until) {
                out.add(new AgendaEntryView(o.getDeliveryStartGameTime(), "BULK_ORDER_START", null, where, income,
                        o.getFillType()));
            }
            if (o.getDeadlineGameTime() > now && o.getDeadlineGameTime() <= until) {
                out.add(new AgendaEntryView(o.getDeadlineGameTime(), "BULK_ORDER_END", null, where, o.getQuantity(),
                        o.getFillType()));
            }
        }
        out.sort(Comparator.comparingLong(AgendaEntryView::gameTime));
        return out;
    }

    /** Fixed dates per FS25 period (1 = March): festivals, tax dates, family occasions and the rotation check. */
    List<YearEventView> yearEvents(Savegame sg, List<Character> members, Integer year) {
        List<YearEventView> out = new ArrayList<>();
        if (props.getFormulas().getClubs().isEnabled()) {
            props.getFormulas().getClubs().getFestivals()
                    .forEach(f -> out.add(new YearEventView(f.getPeriod(), "FESTIVAL", f.getKey())));
        }
        out.add(new YearEventView(1, "TAX_ASSESSMENT", null));
        out.add(new YearEventView(1, "ROTATION_CHECK", null));
        // Roadmap V3.1 R31-D7: assembly and dividend of the cooperative for members, board meetings
        if (sg.getCoopShares() > 0) {
            out.add(new YearEventView(props.getFormulas().getCoopAssembly().getPeriod(), "COOP_ASSEMBLY", null));
            out.add(new YearEventView(props.getFormulas().getCoopShares().getDividendPeriod(), "COOP_DIVIDEND", null));
        }
        if (sg.isCoopBoard()) {
            props.getFormulas().getCoopBoard().getMeetingPeriods()
                    .forEach(p -> out.add(new YearEventView(p, "COOP_BOARD_MEETING", null)));
        }
        if (year != null && tax.quarterlyPrepayment(sg, year) > 0) {
            PREPAYMENT_PERIODS.forEach(p -> out.add(new YearEventView(p, "TAX_PREPAYMENT", null)));
        }
        for (Character c : members) {
            if (c.getOccasionPeriod() == null) {
                continue;
            }
            out.add(new YearEventView(c.getOccasionPeriod(), "FAMILY_BIRTHDAY", c.getName()));
            if (FamilyService.PARTNER.equals(c.getAffiliation())) {
                out.add(new YearEventView((c.getOccasionPeriod() + 5) % 12 + 1, "FAMILY_WEDDING_DAY", c.getName()));
            }
        }
        long yearMs = 12 * gameTime.msPerMonth(sg);
        members.stream().filter(c -> FamilyService.CHILD.equals(c.getAffiliation()))
                .max(Comparator.comparing(Character::getId))
                .filter(c -> sg.getCurrentGameTime() - c.getJoinedAtGameTime() < yearMs)
                .ifPresent(c -> out.add(new YearEventView(props.getFormulas().getFamily().getSchoolStartPeriod(),
                        "SCHOOL_START", c.getName())));
        out.sort(Comparator.comparingInt(YearEventView::period));
        return out;
    }
}
