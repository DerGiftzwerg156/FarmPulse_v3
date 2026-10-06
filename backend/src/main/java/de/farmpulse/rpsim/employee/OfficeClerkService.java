package de.farmpulse.rpsim.employee;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.OfficeReminder;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.OfficeReminderRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-P1: the office clerk with more effect (owner decisions in QUESTIONS.md). The best active clerk (highest
 * effective skill = skill × satisfaction multiplier) counts:
 * <ul>
 *   <li>reminder-days before a deadline she writes once: open tax bills (not when a tax advisor reminds), announced
 *       inspections, the drought-aid application, the end of the delivery month of an open forward contract and the
 *       lease end;</li>
 *   <li>audit factor 1 − audit-reduction-max × effective skill / 100 (TaxService: with an advisor the smaller factor);</li>
 *   <li>on the deadline day she pays an open tax bill herself unless she is overloaded (workload below
 *       overload-workload) - TaxService.</li>
 * </ul>
 */
@Service
public class OfficeClerkService {

    public static final String TAX_BILL = "TAX_BILL";
    public static final String INSPECTION = "INSPECTION";
    public static final String DROUGHT_AID = "DROUGHT_AID";
    public static final String FORWARD_CONTRACT = "FORWARD_CONTRACT";
    public static final String LEASE_END = "LEASE_END";
    /** Roadmap V3.1 R31-B1 / R31-B2 / R31-B5: area payment deadline, bills of the authority and the social insurance. */
    public static final String DIRECT_PAYMENT = "DIRECT_PAYMENT";
    public static final String AUTHORITY_BILL = "AUTHORITY_BILL";

    private final EmployeeRepository employees;
    private final SatisfactionService satisfaction;
    private final SavegameRepository savegames;
    private final ServiceCaseRepository cases;
    private final ForwardContractRepository forwards;
    private final ContractRepository contracts;
    private final OfficeReminderRepository reminders;
    private final de.farmpulse.rpsim.repository.DirectPaymentApplicationRepository directPayments;
    private final NarrationRequestService narration;
    private final FallbackTemplates labels;
    private final RpsimProperties props;

    public OfficeClerkService(EmployeeRepository employees, SatisfactionService satisfaction, SavegameRepository savegames,
                              ServiceCaseRepository cases, ForwardContractRepository forwards, ContractRepository contracts,
                              OfficeReminderRepository reminders, NarrationRequestService narration,
                              FallbackTemplates labels, RpsimProperties props,
                              de.farmpulse.rpsim.repository.DirectPaymentApplicationRepository directPayments) {
        this.directPayments = directPayments;
        this.employees = employees;
        this.satisfaction = satisfaction;
        this.savegames = savegames;
        this.cases = cases;
        this.forwards = forwards;
        this.contracts = contracts;
        this.reminders = reminders;
        this.narration = narration;
        this.labels = labels;
        this.props = props;
    }

    private RpsimProperties.OfficeClerk cfg() {
        return props.getFormulas().getOfficeClerk();
    }

    /** The active clerk with the highest effective skill. */
    public Optional<Employee> bestClerk(Savegame sg) {
        return employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.OFFICE_CLERK).stream()
                .max(Comparator.comparingDouble((Employee e) -> satisfaction.needs(e).effectiveSkill())
                        .thenComparing(Employee::getId, Comparator.reverseOrder()));
    }

    /** Audit factor of the best clerk: 1 − audit-reduction-max × effective skill / 100; 1 without a clerk. */
    public double auditFactor(Savegame sg) {
        return bestClerk(sg).map(e -> auditFactor(satisfaction.needs(e).effectiveSkill(), cfg())).orElse(1.0);
    }

    public static double auditFactor(double effectiveSkill, RpsimProperties.OfficeClerk cfg) {
        return 1 - cfg.getAuditReductionMax() * Math.max(0, Math.min(100, effectiveSkill)) / 100.0;
    }

    /** The clerk who pays a tax bill on the deadline day: the best one, unless she is overloaded. */
    public Optional<Employee> payingClerk(Savegame sg) {
        return bestClerk(sg).filter(e -> satisfaction.needs(e).workload() >= cfg().getOverloadWorkload());
    }

    // ------------------------------------------------------------------------------------------ reminders

    /** Daily, before the tax office (order 78): one reminder per deadline reminder-days ahead. */
    @EventListener
    @Order(76)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Optional<Employee> clerk = bestClerk(sg);
        if (clerk.isEmpty()) {
            return;
        }
        long now = sg.getCurrentGameTime();
        boolean advisor = !contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.TAX_ADVISOR,
                List.of(ContractStatus.ACTIVE)).isEmpty();
        for (ServiceCase sc : cases.findBySavegameOrderByIdDesc(sg)) {
            if (sc.getDeadlineGameTime() == null) {
                continue;
            }
            if (sc.getKind() == CaseKind.TAX_BILL && sc.getStatus() == CaseStatus.AWAITING_PLAYER && !advisor) {
                long amount = sc.getOfferAmount() + (sc.getCostAmount() == null ? 0 : sc.getCostAmount());
                remind(sg, clerk.get(), TAX_BILL, sc.getId(), sc.getDeadlineGameTime(), sc.getTitle(), amount,
                        "/aemter?case=" + sc.getId(), now);
            } else if (sc.getKind() == CaseKind.AUTHORITY_INSPECTION && sc.getStatus() == CaseStatus.IN_PROGRESS) {
                remind(sg, clerk.get(), INSPECTION, sc.getId(), sc.getDeadlineGameTime(),
                        "Kontrolle " + labels.label(sc.getTitle()), null, "/aemter?case=" + sc.getId(), now);
            } else if (sc.getKind() == CaseKind.DROUGHT_AID && sc.getStatus() == CaseStatus.AWAITING_PLAYER) {
                remind(sg, clerk.get(), DROUGHT_AID, sc.getId(), sc.getDeadlineGameTime(), "Antrag Dürrehilfe",
                        sc.getOfferAmount(), "/aemter?case=" + sc.getId(), now);
            } else if ((sc.getKind() == CaseKind.GRANT_REPAYMENT || sc.getKind() == CaseKind.SOCIAL_INSURANCE_BILL)
                    && sc.getStatus() == CaseStatus.AWAITING_PLAYER) {
                long amount = sc.getOfferAmount() + (sc.getCostAmount() == null ? 0 : sc.getCostAmount());
                remind(sg, clerk.get(), AUTHORITY_BILL, sc.getId(), sc.getDeadlineGameTime(), sc.getTitle(), amount,
                        "/aemter?case=" + sc.getId(), now); // R31-B2 / R31-B5
            }
        }
        for (var a : directPayments.findBySavegameOrderByIdDesc(sg)) { // R31-B1: deadline of the area payment
            if (de.farmpulse.rpsim.domain.DirectPaymentApplication.OPEN.equals(a.getStatus())) {
                remind(sg, clerk.get(), DIRECT_PAYMENT, a.getId(), a.getDeadlineGameTime(), "Sammelantrag "
                        + a.getCropYear(), null, "/aemter?directPayment=" + a.getId(), now);
            }
        }
        for (ForwardContract c : forwards.findBySavegameAndStatus(sg, ForwardContract.OPEN)) {
            remind(sg, clerk.get(), FORWARD_CONTRACT, c.getId(), c.getDeadlineGameTime(), "Vorkontrakt "
                    + labels.label(c.getFillType()) + ", " + c.getQuantity() + " l", null, "/market", now);
        }
        for (Contract c : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.LEASE,
                List.of(ContractStatus.ACTIVE))) {
            if (c.getEndsAtGameTime() != null) {
                remind(sg, clerk.get(), LEASE_END, c.getId(), c.getEndsAtGameTime(), "Pachtende Feld " + c.getFarmlandId(),
                        null, "/farmland?contract=" + c.getId(), now);
            }
        }
    }

    /** One reminder per subject and deadline, when the deadline is at most reminder-days ahead (and not passed). */
    boolean remind(Savegame sg, Employee clerk, String type, long subjectId, long deadline, String title, Long amount,
                   String link, long now) {
        if (deadline <= now || deadline - now > GameTime.days(cfg().getReminderDays())
                || reminders.existsBySavegameAndSubjectTypeAndSubjectIdAndDeadlineGameTime(sg, type, subjectId, deadline)) {
            return false;
        }
        OfficeReminder r = new OfficeReminder();
        r.setSavegame(sg);
        r.setSubjectType(type);
        r.setSubjectId(subjectId);
        r.setDeadlineGameTime(deadline);
        r.setSentGameTime(now);
        reminders.save(r);
        narration.request(sg, NarrationEventType.OFFICE_CLERK_REMINDER).from(clerk.getCharacter())
                .facts(NarrationFacts.builder().put("subject", type).put("title", title).put("amount", amount)
                        .put("amountNote", amount == null ? "" : " (" + amount + " €)")
                        .put("daysLeft", Math.max(0, Math.round(GameTime.toDays(deadline - now)))).build())
                .category(CommunicationCategory.EMPLOYEE).formLink(link).submit();
        return true;
    }
}
