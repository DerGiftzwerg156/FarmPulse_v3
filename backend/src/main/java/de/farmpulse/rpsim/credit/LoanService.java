package de.farmpulse.rpsim.credit;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanPayment;
import de.farmpulse.rpsim.domain.LoanPaymentType;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.LoanPaymentRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan lifecycle: disbursement, automatic monthly installments until repayment, the complete default
 * escalation ladder (technical concept "Zahlungsausfall-Eskalation"), deferral requests (Stundung) and early repayments
 * (Sondertilgung: same installment, shorter term; fee above a yearly free limit).
 * <pre>
 * installment overdue    -> reminder (NarrationJob, no money)                           level 1
 * still overdue          -> penalty fee (MONEY_TRANSACTION CREDIT_PENALTY)              level 2
 * still overdue          -> trust loss (negative TrustEvent)                            level 3
 * repeated default       -> CREDIT_CALLBACK of the full remaining debt OR credit block   level 4
 * </pre>
 * T-03: a booking the mod refuses (FAILED, e.g. INSUFFICIENT_FUNDS) is treated like a missed payment: an installment
 * is reversed and becomes due again, an unpaid penalty moves on to the next stage, an uncollected call-back leaves the
 * loan DEFAULTED and the bank collects the debt as soon as liquidity allows.
 */
@Service
public class LoanService {

    public static final String RELATED = "LOAN";

    private final LoanRepository loans;
    private final LoanPaymentRepository payments;
    private final OutboxService outbox;
    private final LiquidityService liquidity;
    private final NarrationRequestService narration;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final CreditConfigResolver configs;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public LoanService(LoanRepository loans, LoanPaymentRepository payments, OutboxService outbox,
                       LiquidityService liquidity, NarrationRequestService narration, CharacterLookup lookup,
                       TrustScoreService trust, PublicActionService publicActions, DiaryService diary,
                       CreditConfigResolver configs, RpsimProperties props, GameTime gameTime) {
        this.loans = loans;
        this.payments = payments;
        this.outbox = outbox;
        this.liquidity = liquidity;
        this.narration = narration;
        this.lookup = lookup;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.configs = configs;
        this.props = props;
        this.gameTime = gameTime;
    }

    /** Creates a loan. Non-legacy loans are disbursed via CREDIT_DISBURSEMENT. */
    @Transactional
    public Loan create(Savegame sg, long principal, double rate, int termMonths, String purpose, boolean legacy,
                       Long applicationId) {
        Loan l = new Loan();
        l.setSavegame(sg);
        l.setApplicationId(applicationId);
        l.setPrincipal(principal);
        l.setRemainingAmount(principal);
        l.setInterestRate(rate);
        l.setTermMonths(termMonths);
        l.setMonthlyInstallment(CreditFormula.monthlyInstallment(principal, rate, termMonths));
        l.setPurpose(purpose);
        l.setStatus(LoanStatus.ACTIVE);
        l.setLegacy(legacy);
        l.setStartedAtGameTime(sg.getCurrentGameTime());
        // T-08: installments are due at the start of each FS25 period ("zum Monatsersten")
        l.setNextDueGameTime(gameTime.addMonths(sg, sg.getCurrentGameTime(), 1));
        loans.save(l);
        if (!legacy) {
            var ins = outbox.money(sg, principal, MoneyReason.CREDIT_DISBURSEMENT, "Auszahlung Kredit: " + purpose,
                    new Related(RELATED, l.getId()));
            payment(l, principal, LoanPaymentType.DISBURSEMENT, ins.getInstructionId());
        }
        return l;
    }

    private LoanPayment payment(Loan l, long amount, LoanPaymentType type, String instructionId) {
        LoanPayment p = new LoanPayment();
        p.setSavegame(l.getSavegame());
        p.setLoan(l);
        p.setGameTime(l.getSavegame().getCurrentGameTime());
        p.setAmount(amount);
        p.setType(type);
        p.setInstructionId(instructionId);
        return payments.save(p);
    }

    /** Called by the PayrollScheduler on every game-time advance. */
    @Transactional
    public void processDueInstallments(Savegame sg) {
        for (Loan l : loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE)) {
            processLoan(sg, l);
        }
        for (Loan l : loans.findBySavegameAndStatus(sg, LoanStatus.DEFAULTED)) {
            collectDefaulted(sg, l);
        }
    }

    /** T-03: an uncollected call-back is booked again as soon as the liquidity covers it. */
    void collectDefaulted(Savegame sg, Loan l) {
        long remaining = l.getRemainingAmount();
        if (remaining <= 0 || liquidity.available(sg) < remaining) {
            return;
        }
        var ins = outbox.money(sg, -remaining, MoneyReason.CREDIT_CALLBACK, "Einzug Restschuld",
                new Related(RELATED, l.getId()));
        payment(l, remaining, LoanPaymentType.CALLBACK, ins.getInstructionId());
        l.setRemainingAmount(0);
        l.setStatus(LoanStatus.CALLED);
        diary.addAuto(sg, "CREDIT", "Restschuld eingezogen", "Die Bank hat die offene Restschuld von " + remaining
                + " € eingezogen.", RELATED, l.getId());
    }

    void processLoan(Savegame sg, Loan l) {
        long now = sg.getCurrentGameTime();
        if (l.getDeferredUntilGameTime() != null && now < l.getDeferredUntilGameTime()) {
            return;
        }
        // pay every due installment as long as liquidity allows
        while (l.getStatus() == LoanStatus.ACTIVE && l.getNextDueGameTime() <= now) {
            long installment = Math.min(l.getMonthlyInstallment(), l.getRemainingAmount() + interest(l));
            if (liquidity.available(sg) < installment) {
                break;
            }
            pay(sg, l, installment);
        }
        if (l.getStatus() != LoanStatus.ACTIVE) {
            return;
        }
        if (l.getNextDueGameTime() > now) {
            if (l.getOverdueSinceGameTime() != null) {
                // caught up: the overdue phase ends, the escalation counter restarts (missed installments stay counted)
                l.setOverdueSinceGameTime(null);
                l.setEscalationLevel(0);
            }
            return;
        }
        registerMisses(l, now);
        escalate(sg, l, now);
    }

    private long interest(Loan l) {
        return Math.round(l.getRemainingAmount() * l.getInterestRate() / 12.0);
    }

    private void pay(Savegame sg, Loan l, long installment) {
        long interest = interest(l);
        long principalPart = Math.max(0, installment - interest);
        // a Sondertilgung shortens the term: count the installments that are actually left
        int total = l.getPaidInstallments() + remainingInstallments(l);
        l.setRemainingAmount(Math.max(0, l.getRemainingAmount() - principalPart));
        l.setPaidInstallments(l.getPaidInstallments() + 1);
        boolean wasOverdue = l.getOverdueSinceGameTime() != null;
        l.setNextDueGameTime(gameTime.addMonths(sg, l.getNextDueGameTime(), 1));
        var ins = outbox.money(sg, -installment, MoneyReason.CREDIT_INSTALLMENT,
                "Kreditrate " + l.getPaidInstallments() + "/" + total, new Related(RELATED, l.getId()));
        LoanPayment p = payment(l, installment, LoanPaymentType.INSTALLMENT, ins.getInstructionId());
        p.setPrincipalPart(principalPart);
        p.setTrustBonusGiven(!wasOverdue);
        if (!wasOverdue) {
            lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, props.getFormulas().getTrust().getOnTimePayment(),
                    TrustReason.ON_TIME_PAYMENT, "Rate pünktlich"));
        }
        if (l.getRemainingAmount() <= 0) {
            paidOff(sg, l);
        }
    }

    private void paidOff(Savegame sg, Loan l) {
        l.setStatus(LoanStatus.PAID_OFF);
        narrate(sg, l, NarrationEventType.CREDIT_PAID_OFF, NarrationFacts.builder()
                .put("principal", l.getPrincipal()).put("purpose", l.getPurpose()).build());
        diary.addAuto(sg, "CREDIT", "Kredit vollständig getilgt", "Der Kredit \"" + l.getPurpose() + "\" über "
                + l.getPrincipal() + " € ist abbezahlt.", RELATED, l.getId());
    }

    /** Installments left with the current installment (a Sondertilgung shortens the term, the installment stays). */
    public int remainingInstallments(Loan l) {
        return CreditFormula.remainingInstallments(l.getRemainingAmount(), l.getInterestRate(), l.getMonthlyInstallment());
    }

    /** Counts each unpaid due date exactly once. */
    private void registerMisses(Loan l, long now) {
        if (l.getOverdueSinceGameTime() == null) {
            l.setOverdueSinceGameTime(l.getNextDueGameTime());
        }
        Savegame sg = l.getSavegame();
        long due = l.getLastMissedDueGameTime() == null || l.getLastMissedDueGameTime() < l.getNextDueGameTime()
                ? l.getNextDueGameTime() : gameTime.addMonths(sg, l.getLastMissedDueGameTime(), 1);
        for (; due <= now; due = gameTime.addMonths(sg, due, 1)) {
            l.setMissedInstallments(l.getMissedInstallments() + 1);
            l.setLastMissedDueGameTime(due);
            payment(l, l.getMonthlyInstallment(), LoanPaymentType.MISSED, null);
        }
    }

    private void escalate(Savegame sg, Loan l, long now) {
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        double overdueDays = GameTime.toDays(now - l.getOverdueSinceGameTime());
        if (l.getEscalationLevel() < 1 && overdueDays >= cfg.getReminderAfterDays()) {
            l.setEscalationLevel(1);
            narrate(sg, l, NarrationEventType.CREDIT_PAYMENT_REMINDER, NarrationFacts.builder()
                    .put("installment", l.getMonthlyInstallment()).put("purpose", l.getPurpose()).build());
        }
        if (l.getEscalationLevel() < 2 && overdueDays >= cfg.getPenaltyAfterDays()) {
            l.setEscalationLevel(2);
            long fee = Math.round(l.getMonthlyInstallment() * cfg.getPenaltyRate());
            var ins = outbox.money(sg, -fee, MoneyReason.CREDIT_PENALTY, "Verzugsgebühr", new Related(RELATED, l.getId()));
            payment(l, fee, LoanPaymentType.PENALTY, ins.getInstructionId());
            narrate(sg, l, NarrationEventType.CREDIT_PENALTY, NarrationFacts.builder().put("penaltyFee", fee)
                    .put("installment", l.getMonthlyInstallment()).build());
        }
        if (l.getEscalationLevel() < 3 && overdueDays >= cfg.getTrustLossAfterDays()) {
            trustLossStage(sg, l, cfg);
        }
        if (l.getEscalationLevel() >= 3 && l.getEscalationLevel() < 4
                && l.getMissedInstallments() >= cfg.getFinalStageAfterMissedInstallments()) {
            l.setEscalationLevel(4);
            if ("BLOCK".equalsIgnoreCase(cfg.getFinalStage())) {
                l.setBlocksNewCredit(true);
                narrate(sg, l, NarrationEventType.CREDIT_BLOCKED, NarrationFacts.builder()
                        .put("missedInstallments", l.getMissedInstallments()).build());
                diary.addAuto(sg, "CREDIT", "Kreditsperre", "Die Bank verweigert künftige Kredite.", RELATED, l.getId());
            } else {
                callBack(sg, l, cfg);
            }
        }
    }

    private void trustLossStage(Savegame sg, Loan l, RpsimProperties.Credit cfg) {
        l.setEscalationLevel(3);
        lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, cfg.getTrustLossDelta(), TrustReason.PAYMENT_ESCALATION,
                "Anhaltender Zahlungsverzug"));
        narrate(sg, l, NarrationEventType.CREDIT_TRUST_WARNING, NarrationFacts.builder()
                .put("missedInstallments", l.getMissedInstallments()).build());
    }

    /**
     * T-03: the mod did not execute a loan booking (FAILED / REJECTED ack). Returns true when the loan was adjusted.
     */
    @Transactional
    public boolean onBookingFailed(Savegame sg, Long loanId, String instructionId, String reason) {
        Loan l = loans.findById(loanId).orElse(null);
        LoanPayment p = payments.findFirstByInstructionId(instructionId).orElse(null);
        if (l == null || p == null || p.getType() == LoanPaymentType.REVERSED) {
            return false;
        }
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        switch (p.getType()) {
            case INSTALLMENT -> reverseInstallment(sg, l, p);
            case SPECIAL_REPAYMENT -> reverseSpecialRepayment(sg, l, p, cfg);
            // the fee is part of the same batch - the Sondertilgung itself is reversed with its own ack
            case PREPAYMENT_FEE -> p.setType(LoanPaymentType.REVERSED);
            case PENALTY -> {
                // unpaid penalty -> next escalation stage right away
                p.setType(LoanPaymentType.REVERSED);
                if (l.getStatus() == LoanStatus.ACTIVE && l.getEscalationLevel() < 3) {
                    trustLossStage(sg, l, cfg);
                }
            }
            case CALLBACK -> {
                p.setType(LoanPaymentType.REVERSED);
                l.setRemainingAmount(l.getRemainingAmount() + p.getAmount());
                l.setStatus(LoanStatus.DEFAULTED);
                l.setBlocksNewCredit(true);
                narrate(sg, l, NarrationEventType.CREDIT_BLOCKED, NarrationFacts.builder()
                        .put("missedInstallments", l.getMissedInstallments()).build());
                diary.addAuto(sg, "CREDIT", "Restschuld nicht eingezogen", "Die Bank konnte die fällige Restschuld von "
                        + p.getAmount() + " € nicht einziehen. Sie bucht sie ab, sobald das Konto es zulässt.",
                        RELATED, l.getId());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The installment was not paid: undo its effects, it is due again and runs into the escalation ladder. */
    private void reverseInstallment(Savegame sg, Loan l, LoanPayment p) {
        long principalPart = p.getPrincipalPart() != null ? p.getPrincipalPart()
                : Math.max(0, p.getAmount() - interest(l));
        p.setType(LoanPaymentType.REVERSED);
        l.setRemainingAmount(l.getRemainingAmount() + principalPart);
        l.setPaidInstallments(Math.max(0, l.getPaidInstallments() - 1));
        l.setNextDueGameTime(gameTime.addMonths(sg, l.getNextDueGameTime(), -1));
        if (l.getStatus() == LoanStatus.PAID_OFF) {
            l.setStatus(LoanStatus.ACTIVE);
        }
        if (Boolean.TRUE.equals(p.getTrustBonusGiven())) {
            lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, -props.getFormulas().getTrust().getOnTimePayment(),
                    TrustReason.ON_TIME_PAYMENT_REVERSED, "Rate nicht gebucht"));
        }
        diary.addAuto(sg, "CREDIT", "Kreditrate nicht gebucht", "Die Rate über " + p.getAmount()
                + " € konnte nicht abgebucht werden und ist wieder fällig.", RELATED, l.getId());
    }

    /** The Sondertilgung was not booked: the debt is back, a trust bonus is taken back. Mails already sent stay. */
    private void reverseSpecialRepayment(Savegame sg, Loan l, LoanPayment p, RpsimProperties.Credit cfg) {
        p.setType(LoanPaymentType.REVERSED);
        l.setRemainingAmount(l.getRemainingAmount() + (p.getPrincipalPart() != null ? p.getPrincipalPart() : p.getAmount()));
        if (l.getStatus() == LoanStatus.PAID_OFF) {
            l.setStatus(LoanStatus.ACTIVE);
        }
        if (Boolean.TRUE.equals(p.getTrustBonusGiven())) {
            lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, -cfg.getSpecialRepaymentTrustDelta(),
                    TrustReason.SPECIAL_REPAYMENT_REVERSED, "Sondertilgung nicht gebucht"));
        }
        diary.addAuto(sg, "CREDIT", "Sondertilgung nicht gebucht", "Die Sondertilgung über " + p.getAmount()
                + " € konnte nicht abgebucht werden. Die Restschuld bleibt unverändert.", RELATED, l.getId());
    }

    private void callBack(Savegame sg, Loan l, RpsimProperties.Credit cfg) {
        long remaining = l.getRemainingAmount();
        var ins = outbox.money(sg, -remaining, MoneyReason.CREDIT_CALLBACK, "Fälligstellung Restschuld",
                new Related(RELATED, l.getId()));
        payment(l, remaining, LoanPaymentType.CALLBACK, ins.getInstructionId());
        l.setRemainingAmount(0);
        l.setStatus(LoanStatus.CALLED);
        l.setBlocksNewCredit(true);
        // A public call-back becomes known in the village (technical concept "Dorf-Ansehen").
        publicActions.record(sg, PublicActionType.PUBLIC_DEFAULT, cfg.getPublicDefaultDelta(),
                "Kredit fällig gestellt: " + l.getPurpose());
        narrate(sg, l, NarrationEventType.CREDIT_CALLBACK, NarrationFacts.builder().put("calledAmount", remaining)
                .put("purpose", l.getPurpose()).build());
        diary.addAuto(sg, "CREDIT", "Kredit fällig gestellt", "Die Bank hat die Restschuld von " + remaining
                + " € sofort fällig gestellt.", RELATED, l.getId());
    }

    public record DeferralResult(boolean granted, String reasonCategory) {
    }

    /** Stundung: formula-based check (config), narrated as mail of the bank advisor. */
    @Transactional
    public DeferralResult requestDeferral(Savegame sg, Long loanId, String playerMessage) {
        Loan l = loans.findById(loanId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("loan " + loanId));
        if (l.getStatus() != LoanStatus.ACTIVE) {
            throw new BusinessRuleException("LOAN_NOT_ACTIVE", "Nur laufende Kredite können gestundet werden.");
        }
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        double history = CreditFormula.paymentHistoryScore(
                payments.countBySavegameAndType(sg, LoanPaymentType.INSTALLMENT),
                payments.countBySavegameAndType(sg, LoanPaymentType.MISSED), cfg);
        String reason;
        if (l.getDeferralsUsed() >= cfg.getMaxDeferralsPerLoan()) {
            reason = "DEFERRAL_LIMIT_REACHED";
        } else if (l.getEscalationLevel() > cfg.getDeferralMaxEscalationLevel()) {
            reason = "ESCALATION_TOO_ADVANCED";
        } else if (history < cfg.getDeferralMinPaymentHistory()) {
            reason = "POOR_PAYMENT_HISTORY";
        } else {
            reason = null;
        }
        boolean granted = reason == null;
        if (granted) {
            long until = gameTime.addMonths(sg, sg.getCurrentGameTime(), cfg.getDeferralMonths());
            l.setDeferredUntilGameTime(until);
            l.setNextDueGameTime(Math.max(l.getNextDueGameTime(), until));
            l.setDeferralsUsed(l.getDeferralsUsed() + 1);
            l.setOverdueSinceGameTime(null);
            l.setEscalationLevel(0);
            payment(l, 0, LoanPaymentType.DEFERRAL, null);
            narrate(sg, l, NarrationEventType.CREDIT_DEFERRAL_GRANTED, NarrationFacts.builder()
                    .put("deferralMonths", cfg.getDeferralMonths()).put("purpose", l.getPurpose()).build(), playerMessage);
            diary.addAuto(sg, "CREDIT", "Stundung gewährt", "Die Bank setzt die Raten für " + cfg.getDeferralMonths()
                    + " Monate aus.", RELATED, l.getId());
        } else {
            narrate(sg, l, NarrationEventType.CREDIT_DEFERRAL_DENIED, NarrationFacts.builder()
                    .put("reasonCategory", reason).put("purpose", l.getPurpose()).build(), playerMessage);
        }
        return new DeferralResult(granted, reason);
    }

    /**
     * What a Sondertilgung of this loan costs right now: the fee-free amount left in the current FS25 year, the fee
     * rate above it and the pro-rata interest a full repayment adds. {@code refusal}: null when allowed, else the code.
     */
    public record SpecialRepaymentTerms(String refusal, long freeAmountLeft, double feeRate, long payoffInterest) {

        public long fee(long amount) {
            return Math.round(Math.max(0, amount - freeAmountLeft) * feeRate);
        }
    }

    public record SpecialRepaymentResult(long amount, long interest, long fee, long remainingAmount,
                                         int remainingInstallments, boolean paidOff, boolean trustBonus) {
    }

    public SpecialRepaymentTerms specialRepaymentTerms(Savegame sg, Loan l) {
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        long now = sg.getCurrentGameTime();
        String refusal;
        if (l.getStatus() != LoanStatus.ACTIVE || l.getRemainingAmount() <= 0) {
            refusal = "LOAN_NOT_ACTIVE";
        } else if (l.getDeferredUntilGameTime() != null && now < l.getDeferredUntilGameTime()) {
            refusal = "LOAN_DEFERRED";
        } else if (l.getOverdueSinceGameTime() != null || l.getNextDueGameTime() <= now) {
            refusal = "LOAN_OVERDUE";
        } else {
            refusal = null;
        }
        long yearStart = gameTime.addMonths(sg, now, -(gameTime.periodOfYear(sg, now) - 1));
        long usedThisYear = payments.findByLoanOrderByGameTimeAscIdAsc(l).stream()
                .filter(p -> p.getType() == LoanPaymentType.SPECIAL_REPAYMENT && p.getGameTime() >= yearStart)
                .mapToLong(p -> p.getPrincipalPart() != null ? p.getPrincipalPart() : p.getAmount()).sum();
        long freeLeft = Math.max(0, Math.round(l.getPrincipal() * cfg.getSpecialRepaymentFreeShare()) - usedThisYear);
        return new SpecialRepaymentTerms(refusal, freeLeft, cfg.getSpecialRepaymentFeeRate(), payoffInterest(sg, l));
    }

    /** Interest of the running month up to now (since the last due date, or since the start of the loan). */
    private long payoffInterest(Savegame sg, Loan l) {
        long next = l.getNextDueGameTime();
        long from = Math.max(gameTime.addMonths(sg, next, -1), l.getStartedAtGameTime());
        if (next <= from) {
            return 0;
        }
        double share = Math.clamp((sg.getCurrentGameTime() - from) / (double) (next - from), 0.0, 1.0);
        return Math.round(interest(l) * share);
    }

    /**
     * Sondertilgung: {@code amount} (1..remaining debt) reduces the debt, the installment stays and the term gets
     * shorter. A full repayment adds the pro-rata interest of the running month. Above the fee-free share of the FS25
     * year the bank books a fee on top (same batch). Refused before booking when the liquidity does not cover it.
     */
    @Transactional
    public SpecialRepaymentResult specialRepayment(Savegame sg, Long loanId, long amount) {
        Loan l = loans.findById(loanId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("loan " + loanId));
        SpecialRepaymentTerms terms = specialRepaymentTerms(sg, l);
        if (terms.refusal() != null) {
            throw new BusinessRuleException(terms.refusal(), switch (terms.refusal()) {
                case "LOAN_DEFERRED" -> "Während einer Stundung ist keine Sondertilgung möglich.";
                case "LOAN_OVERDUE" -> "Solange eine Rate überfällig ist, ist keine Sondertilgung möglich.";
                default -> "Nur laufende Kredite können sondergetilgt werden.";
            });
        }
        long remainingBefore = l.getRemainingAmount();
        if (amount <= 0 || amount > remainingBefore) {
            throw new BusinessRuleException("INVALID_AMOUNT",
                    "Der Betrag muss zwischen 1 € und der Restschuld von " + euro(remainingBefore) + " liegen.");
        }
        boolean paidOff = amount == remainingBefore;
        long interest = paidOff ? terms.payoffInterest() : 0;
        long fee = terms.fee(amount);
        long total = amount + interest + fee;
        if (liquidity.available(sg) < total) {
            throw new BusinessRuleException("INSUFFICIENT_LIQUIDITY", "Das Guthaben reicht für die Sondertilgung von "
                    + euro(total) + " nicht aus.");
        }
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        String batchId = fee > 0 ? "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : null;
        var ins = outbox.money(sg, -(amount + interest), MoneyReason.CREDIT_SPECIAL_REPAYMENT,
                "Sondertilgung Kredit: " + l.getPurpose(), new Related(RELATED, l.getId()), batchId, null);
        LoanPayment p = payment(l, amount + interest, LoanPaymentType.SPECIAL_REPAYMENT, ins.getInstructionId());
        p.setPrincipalPart(amount);
        if (fee > 0) {
            var feeIns = outbox.money(sg, -fee, MoneyReason.CREDIT_PREPAYMENT_FEE, "Vorfälligkeitsentschädigung",
                    new Related(RELATED, l.getId()), batchId, null);
            payment(l, fee, LoanPaymentType.PREPAYMENT_FEE, feeIns.getInstructionId());
        }
        l.setRemainingAmount(remainingBefore - amount);
        boolean trustBonus = amount >= remainingBefore * cfg.getSpecialRepaymentTrustMinShare();
        p.setTrustBonusGiven(trustBonus);
        if (trustBonus) {
            lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, cfg.getSpecialRepaymentTrustDelta(),
                    TrustReason.SPECIAL_REPAYMENT, "Sondertilgung"));
        }
        int left = remainingInstallments(l);
        narrate(sg, l, NarrationEventType.CREDIT_SPECIAL_REPAYMENT, NarrationFacts.builder()
                .put("purpose", l.getPurpose()).put("repaidAmount", amount).put("interestAmount", interest)
                .put("feeAmount", fee).put("remainingAmount", l.getRemainingAmount()).put("remainingInstallments", left)
                .put("paidOff", paidOff)
                .put("detailNote", paidOff
                        ? "Damit ist der Kredit vollständig zurückgezahlt; für den laufenden Monat haben wir anteilige "
                                + "Zinsen von " + euro(interest) + " berechnet."
                        : "Ihre Restschuld beträgt jetzt " + euro(l.getRemainingAmount()) + ". Die monatliche Rate bleibt "
                                + "gleich, dadurch sind es nur noch " + left + " Raten.")
                .put("feeNote", fee > 0 ? "Da Sie die gebührenfreie Sondertilgung für dieses Jahr überschritten haben, "
                        + "berechnen wir eine Vorfälligkeitsentschädigung von " + euro(fee) + "." : "")
                .build());
        diary.addAuto(sg, "CREDIT", "Sondertilgung", "Sondertilgung von " + euro(amount) + " auf den Kredit \""
                + l.getPurpose() + "\"" + (interest > 0 ? " zuzüglich " + euro(interest) + " anteiliger Zinsen" : "")
                + (fee > 0 ? " und " + euro(fee) + " Vorfälligkeitsentschädigung" : "") + ". Restschuld: "
                + euro(l.getRemainingAmount()) + ".", RELATED, l.getId());
        if (paidOff) {
            paidOff(sg, l);
        }
        return new SpecialRepaymentResult(amount, interest, fee, l.getRemainingAmount(), left, paidOff, trustBonus);
    }

    private static String euro(long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount) + " €";
    }

    private void narrate(Savegame sg, Loan l, NarrationEventType type, NarrationFacts facts) {
        narrate(sg, l, type, facts, null);
    }

    private void narrate(Savegame sg, Loan l, NarrationEventType type, NarrationFacts facts, String playerMessage) {
        Character bank = lookup.bank(sg).orElse(null);
        narration.request(sg, type).from(bank).facts(facts).category(CommunicationCategory.CREDIT)
                .related(RELATED, l.getId()).playerMessage(playerMessage).submit();
    }

    /** T-08: "days per period" changed - due dates keep their month, the month start moves. */
    @EventListener
    @Transactional
    public void onCalendarChanged(CalendarChangedEvent e) {
        for (Loan l : loans.findBySavegame_IdAndStatusIn(e.savegameId(), List.of(LoanStatus.ACTIVE, LoanStatus.DEFAULTED))) {
            l.setNextDueGameTime(e.remap(l.getNextDueGameTime()));
            l.setDeferredUntilGameTime(e.remap(l.getDeferredUntilGameTime()));
            l.setLastMissedDueGameTime(e.remap(l.getLastMissedDueGameTime()));
        }
    }

    public List<Loan> list(Savegame sg) {
        return loans.findBySavegameOrderByIdAsc(sg);
    }

    public List<LoanPayment> history(Loan l) {
        return payments.findByLoanOrderByGameTimeAscIdAsc(l);
    }
}
