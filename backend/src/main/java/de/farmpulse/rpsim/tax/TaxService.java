package de.farmpulse.rpsim.tax;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TaxYear;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.LoanPaymentRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TaxYearRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-E1: tax office and tax advisor. Tax year = FS25 year (period 1 = March), data from the booking journal
 * (R2-B). Owner decisions: taxes and fines do not count for the profit, bills are paid by button (app "Ämter"), an audit
 * disputes a share of the expenses under unknown categories and of the excess of expense jump months.
 * <ul>
 *   <li>Start of period 1: the finished year is assessed (bill for a back payment, refund booked at once); prepayments
 *   of the new year at the start of periods 1, 4, 7 and 10 = last assessed tax x prepayment-share / 4.</li>
 *   <li>Unpaid after the deadline: a late fee per started overdue month (booked as FINE with the bill), a reminder,
 *   after enforcement-after-months an enforcement threat (text and trust only, nothing is locked).</li>
 *   <li>Tax advisor (contract): monthly fee, lower tax, a reminder before a deadline, fewer audits.</li>
 *   <li>Roadmap V3 R3-P1: an office clerk lowers the audit chance too (the smaller factor counts) and pays an open bill
 *   herself on the deadline day unless she is overloaded or the balance does not cover it.</li>
 * </ul>
 */
@Service
public class TaxService {

    public static final String RELATED = "TAX";
    public static final String PREPAYMENT = "PREPAYMENT";
    public static final String ASSESSMENT = "ASSESSMENT";
    public static final String AUDIT = "AUDIT";
    /** Marker in ServiceCase.direction: the enforcement threat of this bill was sent. */
    static final String THREATENED = "THREAT";

    private final SavegameRepository savegames;
    private final TaxYearRepository years;
    private final ServiceCaseRepository cases;
    private final ContractRepository contracts;
    private final LoanPaymentRepository payments;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final ContractBillingService billing;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;
    private final de.farmpulse.rpsim.employee.OfficeClerkService clerks;

    public TaxService(SavegameRepository savegames, TaxYearRepository years, ServiceCaseRepository cases,
                      ContractRepository contracts, LoanPaymentRepository payments, FactsService facts,
                      FinanceJournalService journal, LiquidityService liquidity, OutboxService outbox,
                      ContractBillingService billing, ServiceRoleService roles, NarrationRequestService narration,
                      TrustScoreService trust, DiaryService diary, RandomSource random, RpsimProperties props,
                      GameTime gameTime, de.farmpulse.rpsim.employee.OfficeClerkService clerks) {
        this.clerks = clerks;
        this.savegames = savegames;
        this.years = years;
        this.cases = cases;
        this.contracts = contracts;
        this.payments = payments;
        this.facts = facts;
        this.journal = journal;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.billing = billing;
        this.roles = roles;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Tax cfg() {
        return props.getFormulas().getTax();
    }

    public double rate(Savegame sg) {
        return sg.getTonePreset() == TonePreset.HARSH ? cfg().getHardRate() : cfg().getRate();
    }

    public long allowance(Savegame sg) {
        return sg.getTonePreset() == TonePreset.HARSH ? cfg().getHardAllowance() : cfg().getAllowance();
    }

    // ------------------------------------------------------------------------------------------ calendar

    /** Month start: close and assess the finished year (period 1), then bill the prepayment of the quarter. */
    @EventListener
    @Order(77)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (!cfg().isEnabled() || f == null || f.calendar() == null || f.calendar().year() == null) {
            return;
        }
        int year = f.calendar().year();
        int period = gameTime.periodOfYear(sg, e.gameTime());
        TaxYear current = years.findBySavegameAndTaxYear(sg, year).orElseGet(() -> open(sg, year, e.gameTime()));
        if (period == 1) {
            years.findBySavegameAndTaxYear(sg, year - 1).filter(y -> TaxYear.OPEN.equals(y.getStatus()))
                    .ifPresent(y -> assess(sg, y, f));
        }
        if ((period - 1) % 3 == 0) {
            prepayment(sg, current, (period - 1) / 3 + 1);
        }
    }

    private TaxYear open(Savegame sg, int year, long start) {
        TaxYear y = new TaxYear();
        y.setSavegame(sg);
        y.setTaxYear(year);
        y.setStartGameTime(start);
        return years.save(y);
    }

    /** Last assessed tax (the basis of the prepayments), 0 without an assessment. */
    long lastAssessedTax(Savegame sg, int beforeYear) {
        return years.findBySavegameOrderByTaxYearDesc(sg).stream()
                .filter(y -> y.getTaxYear() < beforeYear && TaxYear.ASSESSED.equals(y.getStatus()))
                .findFirst().map(TaxYear::getTax).orElse(0L);
    }

    public long quarterlyPrepayment(Savegame sg, int year) {
        return Math.round(lastAssessedTax(sg, year) * cfg().getPrepaymentShare() / 4.0);
    }

    void prepayment(Savegame sg, TaxYear y, int quarter) {
        long amount = quarterlyPrepayment(sg, y.getTaxYear());
        if (amount <= 0) {
            return;
        }
        ServiceCase bill = bill(sg, PREPAYMENT, y.getTaxYear(), amount,
                "Vorauszahlung Q" + quarter + " Jahr " + y.getTaxYear());
        narration.request(sg, NarrationEventType.TAX_PREPAYMENT).from(bill.getCharacter())
                .facts(NarrationFacts.builder().put("taxYear", y.getTaxYear()).put("quarter", quarter)
                        .put("amount", amount).put("paymentDays", Math.round(cfg().getPaymentDays())).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, bill.getId())
                .formLink("/aemter?case=" + bill.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ assessment

    /** The traceable calculation of a finished year (pure arithmetic on the collected sums). */
    public record Calculation(long income, long expense, long depreciation, long interest, long profit, long allowance,
                              long taxable, double rate, long advisorReduction, long tax) {
    }

    public static Calculation calculate(long income, long expense, long depreciation, long interest, long allowance,
                                        double rate, double advisorShare) {
        long profit = income + expense - depreciation - interest;
        long taxable = Math.max(0, profit - allowance);
        long gross = Math.round(taxable * rate);
        long reduction = Math.round(gross * advisorShare);
        return new Calculation(income, expense, depreciation, interest, profit, allowance, taxable, rate, reduction,
                gross - reduction);
    }

    /** Journal months of a year that count (complete) and the sums without the excluded categories. */
    record YearSums(int months, long income, long expense, List<FinanceJournalService.Month> list) {
    }

    YearSums sums(FarmFacts f, int year) {
        List<FinanceJournalService.Month> list = journal.months(f).stream()
                .filter(m -> m.year() == year && m.complete()).toList();
        double income = 0;
        double expense = 0;
        for (FinanceJournalService.Month m : list) {
            for (FinanceJournalService.Line l : m.lines()) {
                if (cfg().getExcludedCategories().contains(l.category())) {
                    continue;
                }
                if (l.financeClass() == FinanceClass.OPERATING_INCOME) {
                    income += l.amount();
                } else if (l.financeClass() == FinanceClass.OPERATING_EXPENSE) {
                    expense += l.amount();
                }
            }
        }
        return new YearSums(list.size(), Math.round(income), Math.round(expense), list);
    }

    long depreciation(FarmFacts f) {
        if (f.assets() == null) {
            return 0;
        }
        double value = f.assets().vehicles().stream().mapToDouble(v -> v.value() == null ? 0 : v.value()).sum()
                + f.assets().placeables().stream().mapToDouble(p -> p.value() == null ? 0 : p.value()).sum();
        return Math.round(value * cfg().getDepreciationRate());
    }

    long interest(Savegame sg, long from, long to) {
        // installments and the pro-rata interest of a full Sondertilgung
        return Stream.of(LoanPaymentType.INSTALLMENT, LoanPaymentType.SPECIAL_REPAYMENT)
                .flatMap(t -> payments.findBySavegameAndTypeAndGameTimeGreaterThanEqualAndGameTimeLessThan(sg, t, from, to)
                        .stream())
                .filter(p -> p.getPrincipalPart() != null)
                .mapToLong(p -> Math.max(0, p.getAmount() - p.getPrincipalPart())).sum();
    }

    @Transactional
    public TaxYear assess(Savegame sg, TaxYear y, FarmFacts f) {
        long now = sg.getCurrentGameTime();
        YearSums s = sums(f, y.getTaxYear());
        if (s.months() == 0) {
            y.setStatus(TaxYear.NO_DATA); // no journal (older mod): nothing to assess
            return y;
        }
        Calculation c = calculate(s.income(), s.expense(), depreciation(f), interest(sg, y.getStartGameTime(), now),
                allowance(sg), rate(sg), advisor(sg).isPresent() ? cfg().getAdvisorTaxReduction() : 0);
        // open prepayment bills of the year are replaced by the assessment
        long prepaid = 0;
        for (ServiceCase b : bills(sg)) {
            if (PREPAYMENT.equals(b.getReference()) && Integer.valueOf(y.getTaxYear()).equals(b.getQuantity())) {
                if ("PAID".equals(b.getResolution())) {
                    prepaid += b.getOfferAmount();
                } else if (b.getStatus() == CaseStatus.AWAITING_PLAYER) {
                    close(sg, b, CaseStatus.DECLINED, "SUPERSEDED");
                }
            }
        }
        y.setMonths(s.months());
        y.setOperatingIncome(c.income());
        y.setOperatingExpense(c.expense());
        y.setDepreciation(c.depreciation());
        y.setInterest(c.interest());
        y.setProfit(c.profit());
        y.setAllowance(c.allowance());
        y.setTaxable(c.taxable());
        y.setTaxRate(c.rate());
        y.setAdvisorReduction(c.advisorReduction());
        y.setTax(c.tax());
        y.setPrepayments(prepaid);
        y.setBalance(c.tax() - prepaid);
        y.setStatus(TaxYear.ASSESSED);
        y.setAssessedGameTime(now);
        Character office = roles.ensure(sg, CharacterRole.TAX_OFFICE);
        ServiceCase bill = null;
        if (y.getBalance() > 0) {
            bill = bill(sg, ASSESSMENT, y.getTaxYear(), y.getBalance(), "Steuerbescheid Jahr " + y.getTaxYear());
        } else if (y.getBalance() < 0) {
            outbox.money(sg, -y.getBalance(), MoneyReason.TAX_REFUND, "Steuererstattung Jahr " + y.getTaxYear(), null);
        }
        narration.request(sg, NarrationEventType.TAX_ASSESSMENT).from(office)
                .facts(NarrationFacts.builder().put("taxYear", y.getTaxYear()).put("operatingIncome", c.income())
                        .put("operatingExpense", c.expense()).put("depreciation", c.depreciation())
                        .put("interest", c.interest()).put("profit", c.profit()).put("allowance", c.allowance())
                        .put("taxable", c.taxable()).put("taxRatePercent", Math.round(c.rate() * 1000) / 10.0)
                        .put("advisorReduction", c.advisorReduction() > 0 ? c.advisorReduction() : null)
                        .put("tax", c.tax()).put("prepayments", prepaid).put("balance", y.getBalance())
                        .put("paymentDays", bill == null ? null : Math.round(cfg().getPaymentDays())).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, bill == null ? null : bill.getId())
                .formLink(bill == null ? "/aemter" : "/aemter?case=" + bill.getId()).submit();
        diary.addAuto(sg, "CREDIT", "Steuerbescheid Jahr " + y.getTaxYear(), "Gewinn " + c.profit() + " € (Einnahmen "
                + c.income() + " €, Ausgaben " + c.expense() + " €, Abschreibung " + c.depreciation() + " €, Zinsen "
                + c.interest() + " €), zu versteuern " + c.taxable() + " €, Steuer " + c.tax() + " €, Vorauszahlungen "
                + prepaid + " €: " + (y.getBalance() >= 0 ? "Nachzahlung " + y.getBalance() : "Erstattung " + -y.getBalance())
                + " €.", null, null);
        maybeAudit(sg, y, s);
        return y;
    }

    // ------------------------------------------------------------------------------------------ audit

    /**
     * Chance of an audit (lower with a tax advisor or an office clerk - R3-P1: the smaller factor counts); the claim is
     * computed now, the result follows after audit-days.
     */
    void maybeAudit(Savegame sg, TaxYear y, YearSums s) {
        double p = cfg().getAuditProbability() * auditFactor(sg);
        if (!random.chance(p)) {
            return;
        }
        y.setAuditStatus("ANNOUNCED");
        y.setAuditResultGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAuditDays()));
        y.setAuditClaim(auditClaim(s, y.getTaxRate()));
        narration.request(sg, NarrationEventType.TAX_AUDIT_ANNOUNCED).from(roles.ensure(sg, CharacterRole.TAX_OFFICE))
                .facts(NarrationFacts.builder().put("taxYear", y.getTaxYear())
                        .put("auditDays", Math.round(cfg().getAuditDays())).build())
                .category(CommunicationCategory.CONTRACT).submit();
    }

    /** Audit factor: the smaller of the advisor factor (with an advisor) and the office clerk factor (R3-P1). */
    public double auditFactor(Savegame sg) {
        return Math.min(advisor(sg).isPresent() ? cfg().getAdvisorAuditFactor() : 1, clerks.auditFactor(sg));
    }

    /**
     * Owner decision: disputed = expenses under unknown categories + the excess of months whose expenses exceed
     * audit-jump-factor x the monthly average; claim = disputed x audit-disallowed-share x rate.
     */
    long auditClaim(YearSums s, double rate) {
        if (s.months() == 0) {
            return 0;
        }
        double unknown = 0;
        double[] monthly = new double[s.list().size()];
        for (int i = 0; i < s.list().size(); i++) {
            for (FinanceJournalService.Line l : s.list().get(i).lines()) {
                if (l.financeClass() != FinanceClass.OPERATING_EXPENSE
                        || cfg().getExcludedCategories().contains(l.category())) {
                    continue;
                }
                monthly[i] += -l.amount();
                if (!journal.isKnown(l.category())) {
                    unknown += -l.amount();
                }
            }
        }
        double avg = java.util.Arrays.stream(monthly).average().orElse(0);
        double excess = java.util.Arrays.stream(monthly).filter(m -> m > cfg().getAuditJumpFactor() * avg)
                .map(m -> m - avg).sum();
        return Math.round((unknown + excess) * cfg().getAuditDisallowedShare() * rate);
    }

    void auditResults(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (TaxYear y : years.findBySavegameOrderByTaxYearDesc(sg)) {
            if (!"ANNOUNCED".equals(y.getAuditStatus()) || y.getAuditResultGameTime() == null
                    || y.getAuditResultGameTime() > now) {
                continue;
            }
            y.setAuditStatus("DONE");
            long claim = y.getAuditClaim() == null ? 0 : y.getAuditClaim();
            ServiceCase bill = claim > 0 ? bill(sg, AUDIT, y.getTaxYear(), claim, "Nachzahlung Betriebsprüfung Jahr "
                    + y.getTaxYear()) : null;
            narration.request(sg, NarrationEventType.TAX_AUDIT_RESULT).from(roles.ensure(sg, CharacterRole.TAX_OFFICE))
                    .facts(NarrationFacts.builder().put("taxYear", y.getTaxYear()).put("backPayment", claim)
                            .put("paymentDays", bill == null ? null : Math.round(cfg().getPaymentDays())).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, bill == null ? null : bill.getId())
                    .formLink(bill == null ? null : "/aemter?case=" + bill.getId()).submit();
            diary.addAuto(sg, "CREDIT", "Betriebsprüfung Jahr " + y.getTaxYear(),
                    claim > 0 ? "Der Prüfer fordert " + claim + " € nach." : "Die Prüfung verlief ohne Beanstandung.",
                    null, null);
        }
    }

    // ------------------------------------------------------------------------------------------ bills

    List<ServiceCase> bills(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.TAX_BILL));
    }

    ServiceCase bill(Savegame sg, String kind, int year, long amount, String title) {
        long now = sg.getCurrentGameTime();
        ServiceCase b = new ServiceCase();
        b.setSavegame(sg);
        b.setKind(CaseKind.TAX_BILL);
        b.setStatus(CaseStatus.AWAITING_PLAYER);
        b.setCharacter(roles.ensure(sg, CharacterRole.TAX_OFFICE));
        b.setReference(kind);
        b.setQuantity(year);
        b.setTitle(title);
        b.setOfferAmount(amount);
        b.setCostAmount(0L);
        b.setGameTime(now);
        b.setDeadlineGameTime(now + GameTime.days(cfg().getPaymentDays()));
        b.setCreatedAt(Instant.now());
        return cases.save(b);
    }

    private void close(Savegame sg, ServiceCase b, CaseStatus status, String resolution) {
        b.setStatus(status);
        b.setResolution(resolution);
        b.setClosedAtGameTime(sg.getCurrentGameTime());
    }

    /** The player pays a bill by button: tax as TAX_PAYMENT, accumulated late fees as FINE. */
    @Transactional
    public ServiceCase pay(Savegame sg, Long caseId) {
        ServiceCase b = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (b.getKind() != CaseKind.TAX_BILL || b.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Bescheid ist bereits erledigt.");
        }
        long fees = b.getCostAmount() == null ? 0 : b.getCostAmount();
        if (liquidity.available(sg) < b.getOfferAmount() + fees) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Zahlung nicht.");
        }
        close(sg, b, CaseStatus.SETTLED, "PAID");
        b.setPayoutAmount(b.getOfferAmount() + fees);
        outbox.money(sg, -b.getOfferAmount(), MoneyReason.TAX_PAYMENT, b.getTitle(), new Related(RELATED, b.getId()));
        if (fees > 0) {
            outbox.money(sg, -fees, MoneyReason.FINE, "Säumniszuschlag " + b.getTitle(), new Related(RELATED, b.getId()));
        }
        diary.addAuto(sg, "CREDIT", "Steuer bezahlt", b.getTitle() + ": " + b.getOfferAmount() + " €"
                + (fees > 0 ? " zuzüglich " + fees + " € Säumniszuschlag." : "."), RELATED, b.getId());
        return b;
    }

    /** Daily: advisor reminders, late fees / reminders / enforcement threats, audit results. */
    @EventListener
    @Order(78)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        Optional<Contract> advisor = advisor(sg);
        for (ServiceCase b : bills(sg)) {
            if (b.getStatus() != CaseStatus.AWAITING_PLAYER || b.getDeadlineGameTime() == null) {
                continue;
            }
            if (advisor.isPresent() && b.getBaselineCount() == null && now < b.getDeadlineGameTime()
                    && GameTime.toDays(b.getDeadlineGameTime() - now) <= cfg().getAdvisorReminderDays()) {
                b.setBaselineCount(1); // advisor reminded
                narration.request(sg, NarrationEventType.TAX_ADVISOR_REMINDER).from(advisor.get().getCharacter())
                        .facts(NarrationFacts.builder().put("billTitle", b.getTitle()).put("amount", b.getOfferAmount())
                                .put("daysLeft", Math.max(0, Math.round(GameTime.toDays(b.getDeadlineGameTime() - now))))
                                .build())
                        .category(CommunicationCategory.CONTRACT).related(RELATED, b.getId())
                        .formLink("/aemter?case=" + b.getId()).submit();
            }
            if (now <= b.getDeadlineGameTime() && b.getDeadlineGameTime() - now < GameTime.days(1)) {
                payByClerk(sg, b); // R3-P1: the deadline falls before the next day check
            }
            if (b.getStatus() == CaseStatus.AWAITING_PLAYER && now > b.getDeadlineGameTime()) {
                overdue(sg, b, now);
            }
        }
        auditResults(sg);
    }

    /**
     * Roadmap V3 R3-P1: on the deadline day the office clerk pays the bill like the button - not when she is overloaded
     * or the balance does not cover it (then late fees follow as before).
     */
    boolean payByClerk(Savegame sg, ServiceCase b) {
        var clerk = clerks.payingClerk(sg);
        long fees = b.getCostAmount() == null ? 0 : b.getCostAmount();
        if (clerk.isEmpty() || liquidity.available(sg) < b.getOfferAmount() + fees) {
            return false;
        }
        pay(sg, b.getId());
        narration.request(sg, NarrationEventType.OFFICE_CLERK_PAID).from(clerk.get().getCharacter())
                .facts(NarrationFacts.builder().put("billTitle", b.getTitle()).put("amount", b.getOfferAmount() + fees)
                        .build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, b.getId()).submit();
        return true;
    }

    /** One late fee per started overdue game month; reminder on the first, enforcement threat after some months. */
    void overdue(Savegame sg, ServiceCase b, long now) {
        int months = (int) ((now - b.getDeadlineGameTime()) / gameTime.msPerMonth(sg)) + 1;
        if (months <= b.getRoundsUsed()) {
            return;
        }
        long fee = Math.round(b.getOfferAmount() * cfg().getLateFeeRate()) * (months - b.getRoundsUsed());
        b.setCostAmount((b.getCostAmount() == null ? 0 : b.getCostAmount()) + fee);
        b.setRoundsUsed(months);
        Character office = b.getCharacter();
        boolean threat = months >= cfg().getEnforcementAfterMonths() && !THREATENED.equals(b.getDirection());
        if (threat) {
            b.setDirection(THREATENED);
        }
        trust.recordEvent(office, threat ? cfg().getEnforcementTrustDelta() : cfg().getReminderTrustDelta(),
                TrustReason.TAX_OVERDUE, b.getTitle() + " überfällig");
        narration.request(sg, threat ? NarrationEventType.TAX_ENFORCEMENT : NarrationEventType.TAX_REMINDER).from(office)
                .facts(NarrationFacts.builder().put("billTitle", b.getTitle()).put("amount", b.getOfferAmount())
                        .put("lateFees", b.getCostAmount()).put("overdueMonths", months).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, b.getId())
                .formLink("/aemter?case=" + b.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ tax advisor

    public Optional<Contract> advisor(Savegame sg) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.TAX_ADVISOR,
                List.of(ContractStatus.ACTIVE)).stream().findFirst();
    }

    @Transactional
    public Contract offerAdvisor(Savegame sg) {
        if (advisor(sg).isPresent()) {
            throw new BusinessRuleException("ADVISOR_ACTIVE", "Du hast bereits eine Steuerberatung beauftragt.");
        }
        for (Contract old : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.TAX_ADVISOR,
                List.of(ContractStatus.OFFERED))) {
            old.setStatus(ContractStatus.DECLINED);
            old.setEndReason("REPLACED");
        }
        Character advisor = roles.ensure(sg, CharacterRole.TAX_ADVISOR);
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.TAX_ADVISOR);
        c.setStatus(ContractStatus.OFFERED);
        c.setCharacter(advisor);
        c.setMonthlyAmount(cfg().getAdvisorMonthlyFee());
        c.setOfferExpiresAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAdvisorOfferValidDays()));
        c.setCreatedAt(Instant.now());
        contracts.save(c);
        narration.request(sg, NarrationEventType.TAX_ADVISOR_OFFER).from(advisor)
                .facts(NarrationFacts.builder().put("monthlyFee", c.getMonthlyAmount())
                        .put("taxReductionPercent", Math.round(cfg().getAdvisorTaxReduction() * 100))
                        .put("validDays", Math.round(cfg().getAdvisorOfferValidDays())).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/aemter?contract=" + c.getId()).submit();
        return c;
    }

    Contract ownAdvisor(Savegame sg, Long id) {
        return contracts.findById(id)
                .filter(c -> c.getSavegame().getId().equals(sg.getId()) && c.getKind() == ContractKind.TAX_ADVISOR)
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    @Transactional
    public Contract acceptAdvisor(Savegame sg, Long id) {
        Contract c = ownAdvisor(sg, id);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < sg.getCurrentGameTime()) {
            throw new BusinessRuleException("OFFER_EXPIRED", "Das Angebot ist abgelaufen.");
        }
        billing.activate(sg, c);
        diary.addAuto(sg, "CONTRACT", "Steuerberatung beauftragt", c.getMonthlyAmount() + " € pro Monat bei "
                + c.getCharacter().getName() + ".", ContractBillingService.RELATED, c.getId());
        return c;
    }

    @Transactional
    public Contract declineAdvisor(Savegame sg, Long id) {
        Contract c = ownAdvisor(sg, id);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        c.setStatus(ContractStatus.DECLINED);
        c.setEndReason("PLAYER");
        return c;
    }

    @Transactional
    public Contract cancelAdvisor(Savegame sg, Long id) {
        Contract c = ownAdvisor(sg, id);
        if (c.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessRuleException("NOT_ACTIVE", "Die Steuerberatung ist nicht aktiv.");
        }
        c.setStatus(ContractStatus.CANCELLED);
        c.setEndReason("TERMINATED_BY_PLAYER");
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        diary.addAuto(sg, "CONTRACT", "Steuerberatung gekündigt", c.getCharacter().getName() + " ist nicht mehr beauftragt.",
                ContractBillingService.RELATED, c.getId());
        return c;
    }

    // ------------------------------------------------------------------------------------------ overview

    /** Current-year estimate from the journal so far (complete months, without depreciation and interest). */
    public record Overview(Integer currentYear, long incomeSoFar, long expenseSoFar, long estimatedTax, double ratePercent,
                           long allowance, Long nextPrepayment, Integer nextPrepaymentPeriod, TaxYear lastAssessment,
                           boolean advisorActive, boolean journalAvailable) {
    }

    public Overview overview(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = f == null || f.calendar() == null ? null : f.calendar().year();
        Integer period = f == null || f.calendar() == null ? null : f.calendar().period();
        boolean hasJournal = f != null && journal.hasJournal(f);
        YearSums s = year == null || f == null ? new YearSums(0, 0, 0, List.of()) : sums(f, year);
        double share = advisor(sg).isPresent() ? cfg().getAdvisorTaxReduction() : 0;
        long estimated = calculate(s.income(), s.expense(), 0, 0, allowance(sg), rate(sg), share).tax();
        Long next = null;
        Integer nextPeriod = null;
        if (year != null && period != null) {
            int np = ((period - 1) / 3 + 1) * 3 + 1; // start period of the next quarter (13 = next year)
            nextPeriod = np > 12 ? 1 : np;
            long amount = quarterlyPrepayment(sg, np > 12 ? year + 1 : year);
            next = amount > 0 ? amount : null;
        }
        TaxYear last = years.findBySavegameOrderByTaxYearDesc(sg).stream()
                .filter(y -> TaxYear.ASSESSED.equals(y.getStatus())).findFirst().orElse(null);
        return new Overview(year, s.income(), s.expense(), estimated, Math.round(rate(sg) * 1000) / 10.0, allowance(sg),
                next, next == null ? null : nextPeriod, last, advisor(sg).isPresent(), hasJournal);
    }

    public List<ServiceCase> openBills(Savegame sg) {
        return bills(sg).stream().filter(b -> b.getStatus() == CaseStatus.AWAITING_PLAYER).toList();
    }
}
