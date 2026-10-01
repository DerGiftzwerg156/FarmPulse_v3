package de.farmpulse.rpsim.credit;

import java.text.NumberFormat;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bypass.VanillaBypassService;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.narration.FallbackTemplates;
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
import de.farmpulse.rpsim.domain.LoanCollateral;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.finance.FinanceJournalService;
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
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final FieldService fields;
    private final FallbackTemplates labels;
    private final VanillaBypassService bypass;
    private final CollateralService collateral;

    public CreditApplicationService(CreditApplicationRepository applications, CreditScoringService scoring,
                                    LoanService loanService, LoanRepository loans, EmployeeRepository employees,
                                    CreditConfigResolver configs, NarrationRequestService narration,
                                    CharacterLookup lookup, DiaryService diary, RandomSource random,
                                    FactsService facts, FinanceJournalService journal, FieldService fields,
                                    FallbackTemplates labels, VanillaBypassService bypass, CollateralService collateral) {
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
        this.facts = facts;
        this.journal = journal;
        this.fields = fields;
        this.labels = labels;
        this.bypass = bypass;
        this.collateral = collateral;
    }

    @Transactional
    public CreditApplication submit(Savegame sg, long amount, String purpose, int termMonths) {
        return submit(sg, amount, purpose, termMonths, List.of());
    }

    /**
     * Roadmap V3 R3-K1: with own fields as collateral. Coverage = collateral value / amount lowers the rate and eases
     * "loan too large for the farm". A loan above required-above-share of the assets needs collateral for the part
     * above it; when the chosen fields are not enough the bank names more unpledged own fields (largest first) and
     * answers with a counter offer "mit Grundschuld". If all fields together are not enough: normal decision.
     */
    @Transactional
    public CreditApplication submit(Savegame sg, long amount, String purpose, int termMonths, Collection<Integer> farmlandIds) {
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
        // R3-K1: no new credit while a claim after the menu sale of a pledged field is overdue
        if (loans.existsBySavegameAndBlocksNewCreditTrue(sg) || collateral.hasOverdueClaim(sg)) {
            a.setFinalScore(0);
            a.setDecision(CreditDecision.REJECTED);
            a.setReasonCategory(CreditReasonCategory.CREDIT_BLOCKED);
            return applications.save(a);
        }
        // provisional until scored below (the collateral rows need the saved application)
        a.setDecision(CreditDecision.REJECTED);
        a.setReasonCategory(CreditReasonCategory.SOLID_FINANCES);
        applications.save(a);
        long chosen = collateral.request(sg, a, farmlandIds).stream().mapToLong(LoanCollateral::getCollateralValue).sum();
        long needed = Math.round(CreditFormula.requiredCollateral(amount, scoring.totalAssets(sg), cfg));
        long proposed = 0;
        if (needed > 0 && chosen < needed) {
            proposed = collateral.propose(sg, a, needed - chosen).stream().mapToLong(LoanCollateral::getCollateralValue).sum();
            a.setCollateralRequired(proposed > 0);
        }
        long value = chosen + proposed;
        double coverage = CreditFormula.coverage(value, amount);
        double discount = CreditFormula.interestDiscount(coverage, cfg);
        a.setCollateralValue(value);
        a.setCollateralCoverage(coverage);
        a.setInterestDiscount(discount);
        // score is computed immediately on receipt - only its visibility is delayed
        // R2-D1: after repeated vanilla loans new credits cost a surcharge until the vanilla loan is repaid
        double surcharge = bypass.interestSurcharge(sg) - discount;
        CreditFormula.Result r = scoring.score(sg, amount, termMonths, cfg.getBaseInterestRate() + surcharge, value);
        a.setFinalScore(r.finalScore());
        a.setDecision(r.decision());
        a.setReasonCategory(r.reasonCategory());
        if (r.decision() == CreditDecision.APPROVED) {
            a.setOfferedAmount(amount);
            a.setOfferedTermMonths(termMonths);
            a.setOfferedInterestRate(cfg.getBaseInterestRate() + surcharge);
            if (a.isCollateralRequired()) {
                // the requested terms, but only "mit Grundschuld" on the fields the bank names: the sum is too large for
                // the farm without them
                a.setDecision(CreditDecision.COUNTER_OFFER);
                a.setReasonCategory(CreditReasonCategory.LOAN_TOO_LARGE_FOR_FARM);
            }
        } else if (r.decision() == CreditDecision.COUNTER_OFFER) {
            CreditFormula.Terms t = CreditFormula.counterTerms(r.finalScore(), amount, termMonths, cfg);
            a.setOfferedAmount(t.amount());
            a.setOfferedTermMonths(t.termMonths());
            a.setOfferedInterestRate(t.interestRate() + surcharge);
        }
        return a;
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
            // R2-B5: the advisor can name the real figures of the last month
            facts.latest(sg).ifPresent(ff -> {
                journal.putFacts(f, ff);
                putStandingCropFacts(sg, f, ff); // R2-C5: "Ihr Weizen steht gut, das berücksichtigen wir"
            });
            double surcharge = bypass.interestSurcharge(sg);
            if (surcharge > 0) { // R2-D1: the advisor names the surcharge after repeated vanilla loans
                f.put("vanillaSurchargePercent", pct(surcharge));
            }
            NarrationEventType type;
            switch (a.getDecision()) {
                case APPROVED -> {
                    Loan loan = loanService.create(sg, a.getAmount(), a.getOfferedInterestRate(), a.getTermMonths(),
                            a.getPurpose(), false, a.getId());
                    a.setLoanId(loan.getId());
                    collateral.pledge(sg, a, loan); // R3-K1
                    a.setStatus(CreditApplicationStatus.ACCEPTED);
                    f.put("interestRatePercent", pct(a.getOfferedInterestRate()))
                            .put("monthlyInstallment", loan.getMonthlyInstallment());
                    putCollateralFacts(a, f);
                    type = NarrationEventType.CREDIT_APPROVED;
                    diary.addAuto(sg, "CREDIT", "Kredit genehmigt", "Die Bank hat " + a.getAmount() + " € für \""
                            + a.getPurpose() + "\" bewilligt.", RELATED, a.getId());
                }
                case COUNTER_OFFER -> {
                    f.put("offeredAmount", a.getOfferedAmount()).put("offeredTermMonths", a.getOfferedTermMonths())
                            .put("interestRatePercent", pct(a.getOfferedInterestRate()));
                    putCollateralFacts(a, f);
                    type = NarrationEventType.CREDIT_COUNTER_OFFER;
                    diary.addAuto(sg, "CREDIT", "Gegenangebot der Bank", "Statt " + a.getAmount() + " € bietet die Bank "
                            + a.getOfferedAmount() + " € an.", RELATED, a.getId());
                }
                default -> {
                    collateral.drop(sg, a); // R3-K1
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

    /** Roadmap V3 R3-K1: the Grundschuld the decision relies on (chosen fields, fields the bank names). */
    void putCollateralFacts(CreditApplication a, NarrationFacts.Builder f) {
        List<LoanCollateral> list = collateral.ofApplication(a);
        if (list.isEmpty()) {
            return;
        }
        String chosen = CollateralService.fields(list.stream()
                .filter(c -> c.getStatus() != de.farmpulse.rpsim.domain.CollateralStatus.PROPOSED).toList());
        String named = CollateralService.fields(list.stream()
                .filter(c -> c.getStatus() == de.farmpulse.rpsim.domain.CollateralStatus.PROPOSED).toList());
        f.put("collateralValue", a.getCollateralValue()).put("collateralFields", CollateralService.fields(list))
                .put("collateralRequired", a.isCollateralRequired())
                .put("interestDiscountPercent", pct(a.getInterestDiscount()))
                .put("collateralNote", (a.isCollateralRequired()
                        ? "Für diese Summe brauchen wir eine Grundschuld" + (named.isEmpty() ? "" : " auf Feld " + named)
                        + (chosen.isEmpty() ? "" : " zusätzlich zu Feld " + chosen) + ". "
                        : "Die Grundschuld auf Feld " + chosen + " haben wir berücksichtigt. ")
                        + "Der Beleihungswert beträgt " + NumberFormat.getIntegerInstance(Locale.GERMANY)
                        .format(a.getCollateralValue()) + " €, das senkt den Zins um "
                        + pct(a.getInterestDiscount()) + " Prozentpunkte.");
    }

    /** Roadmap V2 R2-C5: value of the standing crops the bank counted and the most valuable crop. */
    void putStandingCropFacts(Savegame sg, NarrationFacts.Builder f, BridgeDtos.FarmFacts ff) {
        List<FieldService.StandingCrop> crops = fields.standingCrops(ff, configs.forSavegame(sg).getStandingCropDiscount());
        long value = Math.round(crops.stream().mapToDouble(FieldService.StandingCrop::value).sum());
        if (value <= 0) {
            return;
        }
        String main = crops.get(0).field().fruitType();
        f.put("standingCropValue", value).put("mainStandingCrop", main)
                .put("standingCropNote", "Ihren stehenden Bestand (" + labels.label(main) + ") haben wir mit rund "
                        + NumberFormat.getIntegerInstance(Locale.GERMANY).format(value) + " € berücksichtigt.");
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
        collateral.pledge(sg, a, loan); // R3-K1: accepting the counter offer pledges the named fields too
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
        collateral.drop(sg, a);
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
