package de.farmpulse.rpsim.credit;

import java.util.List;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.CreditReasonCategory;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CreditApplicationRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit application flow (functional concept "Ablauf eines Antrags"): form input -> score computed
 * immediately -> artificial processing time of 1-2 game days -> decision narrated by the bank advisor ->
 * disbursement + automatic installments. The result is only handed to the narration pipeline once
 * {@code decisionVisibleAtGameTime} is reached (technical concept "Kreditantrag & Bearbeitungszeit").
 */
@Service
public class CreditApplicationService {

    public static final String RELATED = "CREDIT_APPLICATION";

    private final CreditApplicationRepository applications;
    private final CreditScoringService scoring;
    private final LoanService loanService;
    private final LoanRepository loans;
    private final EmployeeRepository employees;
    private final CreditConfigResolver configs;
    private final NarrationRequestService narration;
    private final CharacterLookup lookup;
    private final DiaryService diary;
    private final RandomSource random;

    public CreditApplicationService(CreditApplicationRepository applications, CreditScoringService scoring,
                                    LoanService loanService, LoanRepository loans, EmployeeRepository employees,
                                    CreditConfigResolver configs, NarrationRequestService narration,
                                    CharacterLookup lookup, DiaryService diary, RandomSource random) {
        this.applications = applications;
        this.scoring = scoring;
        this.loanService = loanService;
        this.loans = loans;
        this.employees = employees;
        this.configs = configs;
        this.narration = narration;
        this.lookup = lookup;
        this.diary = diary;
        this.random = random;
    }

    @Transactional
    public CreditApplication submit(Savegame sg, long amount, String purpose, int termMonths) {
        RpsimProperties.Credit cfg = configs.forSavegame(sg);
        if (amount <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Der Betrag muss positiv sein.");
        }
        if (termMonths < cfg.getMinTermMonths() || termMonths > cfg.getMaxTermMonths()) {
            throw new BusinessRuleException("INVALID_TERM", "Laufzeit muss zwischen " + cfg.getMinTermMonths() + " und "
                    + cfg.getMaxTermMonths() + " Monaten liegen.");
        }
        long now = sg.getCurrentGameTime();
        CreditApplication a = new CreditApplication();
        a.setSavegame(sg);
        a.setAmount(amount);
        a.setPurpose(purpose);
        a.setTermMonths(termMonths);
        a.setSubmittedAtGameTime(now);
        a.setDecisionVisibleAtGameTime(now + processingTime(sg, cfg));
        a.setStatus(CreditApplicationStatus.PROCESSING);
        if (loans.existsBySavegameAndBlocksNewCreditTrue(sg)) {
            a.setFinalScore(0);
            a.setDecision(CreditDecision.REJECTED);
            a.setReasonCategory(CreditReasonCategory.CREDIT_BLOCKED);
        } else {
            // score is computed immediately on receipt - only its visibility is delayed
            CreditFormula.Result r = scoring.score(sg, amount, termMonths, cfg.getBaseInterestRate());
            a.setFinalScore(r.finalScore());
            a.setDecision(r.decision());
            a.setReasonCategory(r.reasonCategory());
            if (r.decision() == CreditDecision.APPROVED) {
                a.setOfferedAmount(amount);
                a.setOfferedTermMonths(termMonths);
                a.setOfferedInterestRate(cfg.getBaseInterestRate());
            } else if (r.decision() == CreditDecision.COUNTER_OFFER) {
                CreditFormula.Terms t = CreditFormula.counterTerms(r.finalScore(), amount, termMonths, cfg);
                a.setOfferedAmount(t.amount());
                a.setOfferedTermMonths(t.termMonths());
                a.setOfferedInterestRate(t.interestRate());
            }
        }
        return applications.save(a);
    }

    /** Processing time 1-2 game days, slightly shortened by office clerks (bounded share). */
    long processingTime(Savegame sg, RpsimProperties.Credit cfg) {
        double days = random.uniform(cfg.getProcessingDaysMin(), cfg.getProcessingDaysMax());
        long ms = GameTime.days(days);
        double reductionHours = employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.OFFICE_CLERK)
                .stream().mapToDouble(e -> cfg.getOfficeClerkReductionHours() * e.getSkill() / 100.0).sum();
        long reduction = Math.min(GameTime.hours(reductionHours), Math.round(ms * cfg.getOfficeClerkMaxReductionShare()));
        return ms - reduction;
    }

    /** Called on every game-time advance: hands decisions that became visible to the narration pipeline. */
    @Transactional
    public void releaseVisibleDecisions(Savegame sg) {
        for (CreditApplication a : applications.findBySavegameAndStatus(sg, CreditApplicationStatus.PROCESSING)) {
            if (a.getDecisionVisibleAtGameTime() > sg.getCurrentGameTime()) {
                continue;
            }
            a.setStatus(CreditApplicationStatus.DECIDED);
            a.setNarrated(true);
            NarrationFacts.Builder f = NarrationFacts.builder()
                    .put("decision", a.getDecision())
                    .put("reasonCategory", a.getReasonCategory())
                    .put("requestedAmount", a.getAmount())
                    .put("purpose", a.getPurpose())
                    .put("requestedTermMonths", a.getTermMonths());
            NarrationEventType type;
            switch (a.getDecision()) {
                case APPROVED -> {
                    Loan loan = loanService.create(sg, a.getAmount(), a.getOfferedInterestRate(), a.getTermMonths(),
                            a.getPurpose(), false, a.getId());
                    a.setLoanId(loan.getId());
                    a.setStatus(CreditApplicationStatus.ACCEPTED);
                    f.put("interestRatePercent", pct(a.getOfferedInterestRate()))
                            .put("monthlyInstallment", loan.getMonthlyInstallment());
                    type = NarrationEventType.CREDIT_APPROVED;
                    diary.addAuto(sg, "CREDIT", "Kredit genehmigt", "Die Bank hat " + a.getAmount() + " € für \""
                            + a.getPurpose() + "\" bewilligt.", RELATED, a.getId());
                }
                case COUNTER_OFFER -> {
                    f.put("offeredAmount", a.getOfferedAmount()).put("offeredTermMonths", a.getOfferedTermMonths())
                            .put("interestRatePercent", pct(a.getOfferedInterestRate()));
                    type = NarrationEventType.CREDIT_COUNTER_OFFER;
                    diary.addAuto(sg, "CREDIT", "Gegenangebot der Bank", "Statt " + a.getAmount() + " € bietet die Bank "
                            + a.getOfferedAmount() + " € an.", RELATED, a.getId());
                }
                default -> {
                    type = NarrationEventType.CREDIT_REJECTED;
                    diary.addAuto(sg, "CREDIT", "Kreditantrag abgelehnt", "Der Antrag über " + a.getAmount()
                            + " € für \"" + a.getPurpose() + "\" wurde abgelehnt.", RELATED, a.getId());
                }
            }
            narration.request(sg, type).from(lookup.bank(sg).orElse(null)).facts(f.build())
                    .category(CommunicationCategory.CREDIT).related(RELATED, a.getId())
                    .formLink(type == NarrationEventType.CREDIT_COUNTER_OFFER ? "/bank?application=" + a.getId() : null)
                    .submit();
        }
    }

    private static double pct(Double rate) {
        return rate == null ? 0 : Math.round(rate * 10000) / 100.0;
    }

    @Transactional
    public CreditApplication acceptCounterOffer(Savegame sg, Long id) {
        CreditApplication a = get(sg, id);
        if (a.getStatus() != CreditApplicationStatus.DECIDED || a.getDecision() != CreditDecision.COUNTER_OFFER) {
            throw new BusinessRuleException("NO_OPEN_COUNTER_OFFER", "Es liegt kein offenes Gegenangebot vor.");
        }
        Loan loan = loanService.create(sg, a.getOfferedAmount(), a.getOfferedInterestRate(), a.getOfferedTermMonths(),
                a.getPurpose(), false, a.getId());
        a.setLoanId(loan.getId());
        a.setStatus(CreditApplicationStatus.ACCEPTED);
        diary.addAuto(sg, "CREDIT", "Gegenangebot angenommen", "Kredit über " + a.getOfferedAmount() + " € aufgenommen.",
                RELATED, a.getId());
        return a;
    }

    @Transactional
    public CreditApplication declineCounterOffer(Savegame sg, Long id) {
        CreditApplication a = get(sg, id);
        if (a.getStatus() != CreditApplicationStatus.DECIDED || a.getDecision() != CreditDecision.COUNTER_OFFER) {
            throw new BusinessRuleException("NO_OPEN_COUNTER_OFFER", "Es liegt kein offenes Gegenangebot vor.");
        }
        a.setStatus(CreditApplicationStatus.DECLINED);
        return a;
    }

    public CreditApplication get(Savegame sg, Long id) {
        return applications.findById(id).filter(a -> a.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("credit application " + id));
    }

    public List<CreditApplication> list(Savegame sg) {
        return applications.findBySavegameOrderByIdDesc(sg);
    }
}
