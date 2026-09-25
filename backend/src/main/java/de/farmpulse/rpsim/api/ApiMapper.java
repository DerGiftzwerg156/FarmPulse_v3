package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.*;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.domain.*;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.stereotype.Component;

/** Entity -> view mapping. Trust is only exposed as an abstract level (no raw number). */
@Component
public class ApiMapper {

    private final TrustScoreService trust;
    private final SatisfactionService satisfaction;
    private final LoanService loans;
    private final NegotiationEngine negotiations;
    private final RpsimProperties props;

    public ApiMapper(TrustScoreService trust, SatisfactionService satisfaction, LoanService loans,
                     NegotiationEngine negotiations, RpsimProperties props) {
        this.trust = trust;
        this.satisfaction = satisfaction;
        this.loans = loans;
        this.negotiations = negotiations;
        this.props = props;
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    public CharacterRef ref(Character c) {
        return c == null ? null : new CharacterRef(c.getId(), c.getName(), c.getRole().name(), c.getStatus().name());
    }

    /** VERY_GOOD / GOOD / NEUTRAL / STRAINED / BAD - display abstraction of the trust score. */
    public String trustLevel(Character c) {
        double t = trust.getCurrentTrust(c);
        RpsimProperties.Trust cfg = props.getFormulas().getTrust();
        if (t >= cfg.getDisplayVeryGood()) return "VERY_GOOD";
        if (t >= cfg.getDisplayGood()) return "GOOD";
        if (t > cfg.getDisplayStrained()) return "NEUTRAL";
        if (t > cfg.getDisplayBad()) return "STRAINED";
        return "BAD";
    }

    public MessageView message(Communication c) {
        return new MessageView(c.getId(), c.getThreadRootId(), c.getChannel().name(), c.getInitiatedBy().name(),
                ref(c.getCharacter()), c.getSubject(), c.getBody(), c.getGameTime(), c.isReadFlag(), name(c.getCategory()),
                c.getEventType(), c.getFormLink(), c.isUsedFallback(), name(c.getCallStatus()), c.getRingDeadlineGameTime(),
                c.isOpenTopic(), c.getRelatedEntityType(), c.getRelatedEntityId());
    }

    public CreditApplicationView application(CreditApplication a) {
        boolean visible = a.getStatus() != CreditApplicationStatus.PROCESSING;
        return new CreditApplicationView(a.getId(), a.getAmount(), a.getPurpose(), a.getTermMonths(), a.getStatus().name(),
                a.getSubmittedAtGameTime(), a.getDecisionVisibleAtGameTime(),
                visible ? a.getDecision().name() : null, visible ? a.getReasonCategory().name() : null,
                visible ? a.getOfferedAmount() : null, visible ? a.getOfferedTermMonths() : null,
                visible && a.getOfferedInterestRate() != null ? Math.round(a.getOfferedInterestRate() * 10000) / 100.0 : null,
                a.getLoanId());
    }

    public LoanView loan(Loan l) {
        List<LoanPaymentView> history = loans.history(l).stream()
                .map(p -> new LoanPaymentView(p.getGameTime(), p.getAmount(), p.getType().name())).toList();
        return new LoanView(l.getId(), l.getPrincipal(), l.getRemainingAmount(), Math.round(l.getInterestRate() * 10000) / 100.0,
                l.getTermMonths(), l.getMonthlyInstallment(), l.getPurpose(), l.getStatus().name(), l.isLegacy(),
                l.isBlocksNewCredit(), l.getNextDueGameTime(), l.getOverdueSinceGameTime() != null, l.getEscalationLevel(),
                l.getMissedInstallments(), l.getPaidInstallments(), l.getDeferredUntilGameTime(), history);
    }

    public JobPostingView posting(JobPosting p) {
        return new JobPostingView(p.getId(), p.getJobRole().name(), p.getStatus().name(), p.getCreatedAtGameTime(),
                p.getFilledEmployeeId());
    }

    public ApplicationView application(JobApplication a) {
        return new ApplicationView(a.getId(), ref(a.getCharacter()), a.getCharacter().getBackstory(), a.getSkill(),
                a.getExpectedSalary(), a.getStatus().name());
    }

    public EmployeeView employee(Employee e) {
        SatisfactionService.Needs n = satisfaction.needs(e);
        return new EmployeeView(e.getId(), ref(e.getCharacter()), e.getJobRole().name(), e.getSkill(), e.getMonthlySalary(),
                e.getStatus().name(), new NeedsView(r(n.payFairness()), r(n.workload()), r(n.appreciation()),
                r(n.workingConditions()), r(n.score()), r(n.effectiveSkill())), e.isWarningSent(), e.isSalaryOverdue(),
                e.getTimeOffUntilGameTime());
    }

    private static double r(double v) {
        return Math.round(v * 10) / 10.0;
    }

    public FarmlandView farmland(Savegame sg, FarmlandOwnership o) {
        return new FarmlandView(o.getFarmlandId(), o.getHectares(), o.getReferencePrice(), o.getOwnerType().name(),
                ref(o.getOwnerCharacter()), negotiations.isBlocked(sg, AssetType.FARMLAND, String.valueOf(o.getFarmlandId())));
    }

    public NegotiationView negotiation(Negotiation n) {
        List<OfferView> offers = negotiations.visibleOffers(n).stream()
                .map(o -> new OfferView(o.getRoundNumber(), o.getOfferedBy().name(),
                        o.getCharacter() == null ? null : o.getCharacter().getName(), o.getAmount(), o.getResult().name(),
                        o.getCounterAmount(), o.getGameTime()))
                .filter(o -> !(o.result().equals("BID") && o.amount() == 0))
                .toList();
        return new NegotiationView(n.getId(), n.getAssetType().name(), n.getAssetId(), n.getKind().name(),
                n.getDirection().name(), n.getInitiatedBy().name(), n.getStatus().name(), ref(n.getCounterpartCharacter()),
                ref(n.getAnnouncingCharacter()), n.getBasePrice(), n.getAskingPrice(), n.getRoundsUsed(), n.getMaxRounds(),
                n.getLastCounterOffer(), n.getFinalPrice(), n.getClosesAtGameTime(), ref(n.getWinnerCharacter()), offers);
    }

    public MarketEventView marketEvent(MarketEvent e) {
        // rumours never reveal whether they are accurate or which real event they reference
        return new MarketEventView(e.getId(), e.getEventType().name(), e.getStatus().name(), e.getFillType(), e.getSellPoint(),
                e.getPeakMultiplier(), e.getFixedPrice(), e.getMaxQuantity(), e.getDeadlineGameTime(), e.getSubsidyAmount(),
                e.getStartGameTime(), e.getEndGameTime(), ref(e.getCharacter()), e.getDeliveredQuantity(), e.getEndReason(),
                e.getPlayerParticipation());
    }

    public DiaryView diary(DiaryEntry d) {
        return new DiaryView(d.getId(), d.getGameTime(), GameTime.dayIndex(d.getGameTime()), d.getEntryType().name(),
                d.getCategory(), d.getTitle(), d.getText());
    }
}
