package de.farmpulse.rpsim.authority;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.employee.OfficeClerkService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.diary.PaymentDelayService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B2 / R31-B5 (owner decisions 2026-10-05): bills of the authority (repayment of an investment grant,
 * GRANT_REPAYMENT) and of the agricultural social insurance (annual fee, SOCIAL_INSURANCE_BILL) work like a tax bill
 * (R2-E1): paid by button within tax.payment-days, a late fee of tax.late-fee-rate per started overdue month (booked
 * as FINE with the bill), a reminder on the first and an enforcement threat after tax.enforcement-after-months; the
 * office clerk (R3-P1) pays on the deadline day unless she is overloaded or the balance does not cover it.
 */
@Service
public class AuthorityBillService {

    public static final String RELATED = "AUTHORITY_BILL";
    public static final Set<CaseKind> KINDS = EnumSet.of(CaseKind.GRANT_REPAYMENT, CaseKind.SOCIAL_INSURANCE_BILL);
    /** Marker in ServiceCase.direction: the enforcement threat of this bill was sent. */
    static final String THREATENED = "THREAT";

    private final SavegameRepository savegames;
    private final ServiceCaseRepository cases;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final OfficeClerkService clerks;
    private final PaymentDelayService delays;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public AuthorityBillService(SavegameRepository savegames, ServiceCaseRepository cases, LiquidityService liquidity,
                                OutboxService outbox, NarrationRequestService narration, TrustScoreService trust,
                                DiaryService diary, OfficeClerkService clerks, PaymentDelayService delays,
                                RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.cases = cases;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.clerks = clerks;
        this.delays = delays;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Tax cfg() {
        return props.getFormulas().getTax();
    }

    /** A new bill awaiting the player, due in tax.payment-days. */
    public ServiceCase create(Savegame sg, CaseKind kind, Character from, String title, long amount, String reference) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("not a bill: " + kind);
        }
        long now = sg.getCurrentGameTime();
        ServiceCase b = new ServiceCase();
        b.setSavegame(sg);
        b.setKind(kind);
        b.setStatus(CaseStatus.AWAITING_PLAYER);
        b.setCharacter(from);
        b.setReference(reference);
        b.setTitle(title);
        b.setOfferAmount(amount);
        b.setCostAmount(0L);
        b.setGameTime(now);
        b.setDeadlineGameTime(now + GameTime.days(cfg().getPaymentDays()));
        b.setCreatedAt(Instant.now());
        return cases.save(b);
    }

    public List<ServiceCase> bills(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS);
    }

    static MoneyReason reason(CaseKind kind) {
        return kind == CaseKind.GRANT_REPAYMENT ? MoneyReason.INVESTMENT_GRANT : MoneyReason.SOCIAL_INSURANCE;
    }

    /** The player pays a bill by button: the bill with its money reason, accumulated late fees as FINE. */
    @Transactional
    public ServiceCase pay(Savegame sg, Long caseId) {
        ServiceCase b = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (!KINDS.contains(b.getKind()) || b.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Bescheid ist bereits erledigt.");
        }
        long fees = b.getCostAmount() == null ? 0 : b.getCostAmount();
        if (liquidity.available(sg) < b.getOfferAmount() + fees) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Zahlung nicht.");
        }
        b.setStatus(CaseStatus.SETTLED);
        b.setResolution("PAID");
        b.setClosedAtGameTime(sg.getCurrentGameTime());
        b.setPayoutAmount(b.getOfferAmount() + fees);
        outbox.money(sg, -b.getOfferAmount(), reason(b.getKind()), b.getTitle(), new Related(RELATED, b.getId()));
        if (fees > 0) {
            outbox.money(sg, -fees, MoneyReason.FINE, "Säumniszuschlag " + b.getTitle(), new Related(RELATED, b.getId()));
        }
        diary.addAuto(sg, "CREDIT", "Bescheid bezahlt", b.getTitle() + ": " + b.getOfferAmount() + " €"
                + (fees > 0 ? " zuzüglich " + fees + " € Säumniszuschlag." : "."), RELATED, b.getId());
        return b;
    }

    /** Daily, after the office clerk's reminders (76): clerk payment on the deadline day, late fees and reminders. */
    @EventListener
    @Order(78)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase b : bills(sg)) {
            if (b.getStatus() != CaseStatus.AWAITING_PLAYER || b.getDeadlineGameTime() == null) {
                continue;
            }
            if (now <= b.getDeadlineGameTime() && b.getDeadlineGameTime() - now < GameTime.days(1)) {
                payByClerk(sg, b); // the deadline falls before the next day check
            }
            if (b.getStatus() == CaseStatus.AWAITING_PLAYER && now > b.getDeadlineGameTime()) {
                overdue(sg, b, now);
            }
        }
    }

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
        delays.record(sg, de.farmpulse.rpsim.domain.PaymentDelay.AUTHORITY_BILL, now);
        boolean threat = months >= cfg().getEnforcementAfterMonths() && !THREATENED.equals(b.getDirection());
        if (threat) {
            b.setDirection(THREATENED);
        }
        trust.recordEvent(b.getCharacter(), threat ? cfg().getEnforcementTrustDelta() : cfg().getReminderTrustDelta(),
                TrustReason.AUTHORITY_BILL_OVERDUE, b.getTitle() + " überfällig");
        narration.request(sg, threat ? NarrationEventType.AUTHORITY_BILL_ENFORCEMENT : NarrationEventType.AUTHORITY_BILL_REMINDER)
                .from(b.getCharacter())
                .facts(NarrationFacts.builder().put("billTitle", b.getTitle()).put("amount", b.getOfferAmount())
                        .put("lateFees", b.getCostAmount()).put("overdueMonths", months).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, b.getId())
                .formLink("/aemter?case=" + b.getId()).submit();
    }
}
