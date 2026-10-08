package de.farmpulse.rpsim.credit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.finance.FarmReportService.Report;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-K3: annual review with the bank advisor (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>At the year change, after the farm report, the advisor invites (case ANNUAL_REVIEW, invitation-days); the mail
 *   comments on the year with the report figures as facts. The credit score at the cut-off date is computed then (the
 *   farm as it stands, no new loan) and kept with the invitation.</li>
 *   <li>"Termin wahrnehmen": score &ge; good-score - the advisor offers a rate cut on every running loan (case
 *   ANNUAL_REVIEW_OFFER, offer-days); below bad-score only a serious talk; in between a neutral talk. Running loans are
 *   never tightened automatically.</li>
 *   <li>An accepted cut: rate-cut per loan, at most max-cut-per-loan over its term, never below min-rate; the remaining
 *   term stays, the installment is recalculated (annuity). Declining or ignoring has no consequence.</li>
 * </ul>
 */
@Service
public class AnnualReviewService {

    public static final String RELATED = "ANNUAL_REVIEW";

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CreditScoringService scoring;
    private final CreditConfigResolver configs;
    private final LoanRepository loans;
    private final LoanService loanService;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final de.farmpulse.rpsim.investor.InvestorLedger investors;

    public AnnualReviewService(ServiceCaseRepository cases, SavegameRepository savegames, CreditScoringService scoring,
                               CreditConfigResolver configs, LoanRepository loans, LoanService loanService,
                               CharacterLookup lookup, NarrationRequestService narration, DiaryService diary,
                               de.farmpulse.rpsim.investor.InvestorLedger investors) {
        this.investors = investors;
        this.cases = cases;
        this.savegames = savegames;
        this.scoring = scoring;
        this.configs = configs;
        this.loans = loans;
        this.loanService = loanService;
        this.lookup = lookup;
        this.narration = narration;
        this.diary = diary;
    }

    private RpsimProperties.AnnualReview cfg(Savegame sg) {
        return configs.forSavegame(sg).getAnnualReview();
    }

    /** Year change: invitation with the farm report as facts of the advisor's comment. */
    @Transactional
    public ServiceCase invite(Savegame sg, Report r) {
        RpsimProperties.AnnualReview cfg = cfg(sg);
        Character bank = lookup.bank(sg).orElse(null);
        if (!cfg.isEnabled() || bank == null) {
            return null;
        }
        RpsimProperties.Credit credit = configs.forSavegame(sg);
        double score = scoring.score(sg, 0, credit.getMinTermMonths(), credit.getBaseInterestRate()).finalScore();
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.ANNUAL_REVIEW);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(bank);
        sc.setQuantity(r.year());
        sc.setReference(String.format(Locale.ROOT, "%.1f", score)); // score at the cut-off date (never shown)
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg.getInvitationDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        long harvested = r.fields().stream().filter(f -> f.harvested()).count();
        long yield = r.fields().stream().filter(f -> f.yieldLiters() != null).mapToLong(f -> f.yieldLiters()).sum();
        NarrationFacts.Builder f = NarrationFacts.builder().put("year", r.year()).put("months", r.months())
                .put("operatingIncome", r.totals().operatingIncome())
                .put("operatingExpense", -r.totals().operatingExpense())
                .put("operatingResult", r.totals().operatingResult())
                .put("investment", -r.totals().investment())
                .put("tax", r.tax() == null ? null : r.tax().tax())
                .put("harvestedFields", harvested).put("yieldLiters", yield > 0 ? yield : null)
                .put("rainHours", Math.round(r.rain().stream().mapToDouble(x -> x.rainHours()).sum()))
                .put("staff", r.snapshot().staff())
                .put("previousStaff", r.previous() == null ? null : r.previous().staff())
                .put("reputationTier", r.snapshot().reputationTier())
                .put("previousReputationTier", r.previous() == null ? null : r.previous().reputationTier())
                .put("invitationDays", Math.round(cfg.getInvitationDays()))
                // Roadmap V3.2 R32-I6: the advisor comments new investors and open investor claims
                .put("investorNote", investors.reviewNote(sg, r.investors()));
        narration.request(sg, NarrationEventType.ANNUAL_REVIEW_INVITATION).from(bank).facts(f.build())
                .category(CommunicationCategory.CREDIT).related(RELATED, sc.getId()).formLink("/bank?case=" + sc.getId())
                .submit();
        return sc;
    }

    /** "Termin wahrnehmen": the talk follows the score at the cut-off date. */
    @Transactional
    public ServiceCase attend(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.ANNUAL_REVIEW);
        RpsimProperties.AnnualReview cfg = cfg(sg);
        long now = sg.getCurrentGameTime();
        sc.setStatus(CaseStatus.SETTLED);
        sc.setClosedAtGameTime(now);
        double score = Double.parseDouble(sc.getReference());
        Character bank = sc.getCharacter();
        List<Loan> cuttable = cuttable(sg, cfg);
        NarrationEventType type;
        NarrationFacts.Builder f = NarrationFacts.builder().put("year", sc.getQuantity());
        if (score >= cfg.getGoodScore() && !cuttable.isEmpty()) {
            sc.setResolution("OFFER");
            ServiceCase offer = new ServiceCase();
            offer.setSavegame(sg);
            offer.setKind(CaseKind.ANNUAL_REVIEW_OFFER);
            offer.setStatus(CaseStatus.AWAITING_PLAYER);
            offer.setCharacter(bank);
            offer.setQuantity(sc.getQuantity());
            offer.setReference(String.format(Locale.ROOT, "%.4f", cfg.getRateCut()));
            offer.setGameTime(now);
            offer.setDeadlineGameTime(now + GameTime.days(cfg.getOfferDays()));
            offer.setCreatedAt(Instant.now());
            cases.save(offer);
            f.put("rateCutPercent", pct(cfg.getRateCut())).put("loans", cuttable.size())
                    .put("offerDays", Math.round(cfg.getOfferDays()));
            type = NarrationEventType.ANNUAL_REVIEW_OFFER;
            narration.request(sg, type).from(bank).facts(f.build()).category(CommunicationCategory.CREDIT)
                    .related(RELATED, offer.getId()).formLink("/bank?case=" + offer.getId()).submit();
        } else {
            boolean serious = score < cfg.getBadScore();
            sc.setResolution(serious ? "SERIOUS" : "NEUTRAL");
            f.put("goodResult", score >= cfg.getGoodScore()).put("runningLoans", !cuttable.isEmpty());
            type = serious ? NarrationEventType.ANNUAL_REVIEW_SERIOUS : NarrationEventType.ANNUAL_REVIEW_NEUTRAL;
            narration.request(sg, type).from(bank).facts(f.build()).category(CommunicationCategory.CREDIT)
                    .related(RELATED, sc.getId()).submit();
        }
        diary.addAuto(sg, "CREDIT", "Jahresgespräch " + sc.getQuantity(), switch (type) {
            case ANNUAL_REVIEW_OFFER -> "Die Bank bietet eine Zinssenkung von " + pct(cfg.getRateCut())
                    + " Prozentpunkten auf die laufenden Kredite an.";
            case ANNUAL_REVIEW_SERIOUS -> "Ein ernstes Gespräch mit der Bank über das vergangene Jahr.";
            default -> "Ein ruhiges Gespräch mit der Bank über das vergangene Jahr.";
        }, RELATED, sc.getId());
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.ANNUAL_REVIEW);
        sc.setStatus(CaseStatus.DECLINED);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        return sc;
    }

    /** The rate cut is accepted: every running loan, capped; the installment follows for the remaining term. */
    @Transactional
    public ServiceCase acceptOffer(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.ANNUAL_REVIEW_OFFER);
        RpsimProperties.AnnualReview cfg = cfg(sg);
        double cut = Double.parseDouble(sc.getReference());
        List<String> lines = new ArrayList<>();
        for (Loan l : cuttable(sg, cfg)) {
            double applied = cutFor(l, cut, cfg);
            int left = loanService.remainingInstallments(l);
            double rate = l.getInterestRate() - applied;
            l.setInterestRate(rate);
            l.setRateCutTotal(l.getRateCutTotal() + applied);
            l.setMonthlyInstallment(CreditFormula.monthlyInstallment(l.getRemainingAmount(), rate, Math.max(1, left)));
            lines.add("\"" + l.getPurpose() + "\" auf " + pct(rate) + " % (Rate " + LoanService.euro(l.getMonthlyInstallment()) + ")");
        }
        sc.setStatus(CaseStatus.SETTLED);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        diary.addAuto(sg, "CREDIT", "Zinssenkung angenommen", lines.isEmpty() ? "Kein Kredit läuft mehr."
                : "Neuer Zins: " + String.join("; ", lines) + ".", RELATED, sc.getId());
        return sc;
    }

    @Transactional
    public ServiceCase declineOffer(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.ANNUAL_REVIEW_OFFER);
        sc.setStatus(CaseStatus.DECLINED);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        return sc;
    }

    /** Running loans that can still get a cut (cap per loan and minimum rate). */
    List<Loan> cuttable(Savegame sg, RpsimProperties.AnnualReview cfg) {
        return loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE).stream()
                .filter(l -> l.getRemainingAmount() > 0 && cutFor(l, cfg.getRateCut(), cfg) > 1e-9).toList();
    }

    static double cutFor(Loan l, double cut, RpsimProperties.AnnualReview cfg) {
        double room = Math.min(cfg.getMaxCutPerLoan() - l.getRateCutTotal(), l.getInterestRate() - cfg.getMinRate());
        return Math.max(0, Math.min(cut, room));
    }

    private ServiceCase open(Savegame sg, Long id, CaseKind kind) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != kind || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Termin ist bereits erledigt.");
        }
        return sc;
    }

    /** Daily: invitation and offer lapse without consequence after their deadline. */
    @EventListener
    @Order(79)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if ((sc.getKind() == CaseKind.ANNUAL_REVIEW || sc.getKind() == CaseKind.ANNUAL_REVIEW_OFFER)
                    && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() <= now) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setClosedAtGameTime(now);
            }
        }
    }

    public List<ServiceCase> list(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.ANNUAL_REVIEW, CaseKind.ANNUAL_REVIEW_OFFER));
    }

    static double pct(double rate) {
        return Math.round(rate * 10000) / 100.0;
    }
}
