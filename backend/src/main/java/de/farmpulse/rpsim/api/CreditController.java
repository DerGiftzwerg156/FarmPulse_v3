package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.CreditApplicationRequest;
import de.farmpulse.rpsim.api.Requests.DeferralRequest;
import de.farmpulse.rpsim.api.Requests.SpecialRepaymentRequest;
import de.farmpulse.rpsim.api.Views.CollateralOptionView;
import de.farmpulse.rpsim.api.Views.CollateralOverviewView;
import de.farmpulse.rpsim.api.Views.CollateralView;
import de.farmpulse.rpsim.api.Views.CreditApplicationView;
import de.farmpulse.rpsim.api.Views.DeferralView;
import de.farmpulse.rpsim.api.Views.LoanView;
import de.farmpulse.rpsim.api.Views.SpecialRepaymentView;
import de.farmpulse.rpsim.credit.CollateralService;
import de.farmpulse.rpsim.credit.CreditApplicationService;
import de.farmpulse.rpsim.credit.CreditConfigResolver;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.finance.FarmReportService;
import de.farmpulse.rpsim.finance.LiquidityPlanService;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreditController {

    private final SavegameContext context;
    private final CreditApplicationService applications;
    private final LoanService loans;
    private final ApiMapper mapper;
    private final CollateralService collateral;
    private final CreditScoringService scoring;
    private final CreditConfigResolver configs;
    private final LiquidityPlanService liquidityPlan;
    private final FarmReportService farmReports;

    public CreditController(SavegameContext context, CreditApplicationService applications, LoanService loans,
                            ApiMapper mapper, CollateralService collateral, CreditScoringService scoring,
                            CreditConfigResolver configs, LiquidityPlanService liquidityPlan,
                            FarmReportService farmReports) {
        this.context = context;
        this.applications = applications;
        this.loans = loans;
        this.mapper = mapper;
        this.collateral = collateral;
        this.scoring = scoring;
        this.configs = configs;
        this.liquidityPlan = liquidityPlan;
        this.farmReports = farmReports;
    }

    /** Form: amount, purpose, term. The decision stays hidden ("in Bearbeitung") until decisionVisibleAtGameTime. */
    @PostMapping("/api/credit-applications")
    @Transactional
    public CreditApplicationView apply(@Valid @RequestBody CreditApplicationRequest r) {
        return mapper.application(applications.submit(context.requireActive(), r.amount(), r.purpose(), r.termMonths(),
                r.farmlandIds() == null ? List.of() : r.farmlandIds()));
    }

    @GetMapping("/api/credit-applications")
    @Transactional(readOnly = true)
    public List<CreditApplicationView> applications() {
        return applications.list(context.requireActive()).stream().map(mapper::application).toList();
    }

    @PostMapping("/api/credit-applications/{id}/accept-counter")
    @Transactional
    public CreditApplicationView acceptCounter(@PathVariable Long id) {
        return mapper.application(applications.acceptCounterOffer(context.requireActive(), id));
    }

    @PostMapping("/api/credit-applications/{id}/decline-counter")
    @Transactional
    public CreditApplicationView declineCounter(@PathVariable Long id) {
        return mapper.application(applications.declineCounterOffer(context.requireActive(), id));
    }

    /** Roadmap V3 R3-K1: own fields for the credit form (collateral value) and the pledged fields. */
    @GetMapping("/api/credit/collateral")
    @Transactional(readOnly = true)
    public CollateralOverviewView collateral() {
        Savegame sg = context.requireActive();
        var cfg = configs.forSavegame(sg);
        long threshold = Math.round(cfg.getCollateral().getRequiredAboveShare() * scoring.totalAssets(sg));
        return new CollateralOverviewView(cfg.getCollateral().getLoanToValue() * 100,
                cfg.getCollateral().getRequiredAboveShare() * 100,
                Math.round(cfg.getCollateral().getMaxInterestDiscount() * 10000) / 100.0, threshold,
                collateral.eligible(sg).stream().map(o -> new CollateralOptionView(o.farmlandId(), o.hectares(), o.price(),
                        o.collateralValue())).toList(),
                collateral.pledged(sg).stream().map(mapper::collateral).toList());
    }

    /** Roadmap V3 R3-K1: ask the bank to agree to the sale of a pledged field (the proceeds repay the collateral value). */
    @PostMapping("/api/credit/collateral/{farmlandId}/sale-consent")
    @Transactional
    public CollateralView saleConsent(@PathVariable int farmlandId) {
        return mapper.collateral(collateral.requestSaleConsent(context.requireActive(), farmlandId));
    }

    /** Roadmap V3 R3-L1: ask the bank to agree to lease out a pledged field (the Grundschuld stays). */
    @PostMapping("/api/credit/collateral/{farmlandId}/lease-consent")
    @Transactional
    public CollateralView leaseConsent(@PathVariable int farmlandId) {
        return mapper.collateral(collateral.requestLeaseConsent(context.requireActive(), farmlandId));
    }

    /** Roadmap V3 R3-K2: the next FS25 months with known postings and the income estimate. */
    @GetMapping("/api/liquidity-plan")
    @Transactional(readOnly = true)
    public LiquidityPlanService.Plan liquidityPlan() {
        return liquidityPlan.plan(context.requireActive());
    }

    /** Roadmap V3 R3-K3: farm reports of the finished FS25 years, newest first. */
    @GetMapping("/api/farm-reports")
    @Transactional(readOnly = true)
    public List<FarmReportService.Report> farmReports() {
        return farmReports.list(context.requireActive());
    }

    @GetMapping("/api/loans")
    @Transactional(readOnly = true)
    public List<LoanView> loans() {
        return loans.list(context.requireActive()).stream().map(mapper::loan).toList();
    }

    @PostMapping("/api/loans/{id}/stundung")
    @Transactional
    public DeferralView deferral(@PathVariable Long id, @Valid @RequestBody(required = false) DeferralRequest r) {
        LoanService.DeferralResult d = loans.requestDeferral(context.requireActive(), id, r == null ? null : r.message());
        return new DeferralView(d.granted(), d.reasonCategory());
    }

    @PostMapping("/api/loans/{id}/sondertilgung")
    @Transactional
    public SpecialRepaymentView specialRepayment(@PathVariable Long id, @Valid @RequestBody SpecialRepaymentRequest r) {
        LoanService.SpecialRepaymentResult s = loans.specialRepayment(context.requireActive(), id, r.amount());
        return new SpecialRepaymentView(s.amount(), s.interest(), s.fee(), s.remainingAmount(), s.remainingInstallments(),
                s.paidOff());
    }
}
