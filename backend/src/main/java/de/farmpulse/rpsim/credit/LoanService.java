package de.farmpulse.rpsim.credit;

import java.util.List;

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
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan lifecycle: disbursement, automatic monthly installments until repayment, the complete default
 * escalation ladder (technical concept "Zahlungsausfall-Eskalation") and deferral requests (Stundung).
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
        l.setNextDueGameTime(sg.getCurrentGameTime() + gameTime.msPerMonth());
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
        l.setRemainingAmount(Math.max(0, l.getRemainingAmount() - principalPart));
        l.setPaidInstallments(l.getPaidInstallments() + 1);
        boolean wasOverdue = l.getOverdueSinceGameTime() != null;
        l.setNextDueGameTime(l.getNextDueGameTime() + gameTime.msPerMonth());
        var ins = outbox.money(sg, -installment, MoneyReason.CREDIT_INSTALLMENT,
                "Kreditrate " + l.getPaidInstallments() + "/" + l.getTermMonths(), new Related(RELATED, l.getId()));
        LoanPayment p = payment(l, installment, LoanPaymentType.INSTALLMENT, ins.getInstructionId());
        p.setPrincipalPart(principalPart);
        p.setTrustBonusGiven(!wasOverdue);
        if (!wasOverdue) {
            lookup.bank(sg).ifPresent(b -> trust.recordEvent(b, props.getFormulas().getTrust().getOnTimePayment(),
                    TrustReason.ON_TIME_PAYMENT, "Rate pünktlich"));
        }
        if (l.getRemainingAmount() <= 0) {
            l.setStatus(LoanStatus.PAID_OFF);
            narrate(sg, l, NarrationEventType.CREDIT_PAID_OFF, NarrationFacts.builder()
                    .put("principal", l.getPrincipal()).put("purpose", l.getPurpose()).build());
            diary.addAuto(sg, "CREDIT", "Kredit vollständig getilgt", "Der Kredit \"" + l.getPurpose() + "\" über "
                    + l.getPrincipal() + " € ist abbezahlt.", RELATED, l.getId());
        }
    }

    /** Counts each unpaid due date exactly once. */
    private void registerMisses(Loan l, long now) {
        if (l.getOverdueSinceGameTime() == null) {
            l.setOverdueSinceGameTime(l.getNextDueGameTime());
        }
        long month = gameTime.msPerMonth();
        long due = l.getLastMissedDueGameTime() == null || l.getLastMissedDueGameTime() < l.getNextDueGameTime()
                ? l.getNextDueGameTime() : l.getLastMissedDueGameTime() + month;
        for (; due <= now; due += month) {
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
        l.setNextDueGameTime(l.getNextDueGameTime() - gameTime.msPerMonth());
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
            long until = sg.getCurrentGameTime() + cfg.getDeferralMonths() * gameTime.msPerMonth();
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

    private void narrate(Savegame sg, Loan l, NarrationEventType type, NarrationFacts facts) {
        narrate(sg, l, type, facts, null);
    }

    private void narrate(Savegame sg, Loan l, NarrationEventType type, NarrationFacts facts, String playerMessage) {
        Character bank = lookup.bank(sg).orElse(null);
        narration.request(sg, type).from(bank).facts(facts).category(CommunicationCategory.CREDIT)
                .related(RELATED, l.getId()).playerMessage(playerMessage).submit();
    }

    public List<Loan> list(Savegame sg) {
        return loans.findBySavegameOrderByIdAsc(sg);
    }

    public List<LoanPayment> history(Loan l) {
        return payments.findByLoanOrderByGameTimeAscIdAsc(l);
    }
}
