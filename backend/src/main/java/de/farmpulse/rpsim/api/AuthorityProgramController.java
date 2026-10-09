package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.authority.AnimalDiseaseService;
import de.farmpulse.rpsim.authority.BurdeningEvents;
import de.farmpulse.rpsim.authority.DirectPaymentService;
import de.farmpulse.rpsim.authority.InvestmentGrantService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.InvestmentGrant;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.GameTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3.1 section B for the app "Ämter": area payment application (B1), investment grant (B2) and animal
 * diseases (B4). Bills (GRANT_REPAYMENT, SOCIAL_INSURANCE_BILL) and inspections run as service cases
 * ({@code /api/cases}).
 */
@RestController
public class AuthorityProgramController {

    // ------------------------------------------------------------------------------------------ B1 views

    public record DirectPaymentFieldView(int farmlandId, String fieldName, double hectares, String declaredCrop,
                                         String actualCrop, boolean rotationRepeat) {
    }

    public record DirectPaymentFormFieldView(int farmlandId, String fieldName, double hectares, String suggestedCrop) {
    }

    public record DirectPaymentView(Long id, int cropYear, String status, long openedGameTime, long deadlineGameTime,
                                    long lateLimitGameTime, Long submittedGameTime, int lateDays, String checkStatus,
                                    Double deviatingHectares, Long deviationCut, Long rotationCut, Long lateCut,
                                    Long premium, Long paidAmount, List<DirectPaymentFieldView> fields) {
    }

    public record DirectPaymentStatusView(boolean enabled, double premiumPerHa, double lateCutPercentPerDay, int lateMaxDays,
                                          List<String> crops, List<DirectPaymentFormFieldView> form,
                                          List<DirectPaymentView> applications) {
    }

    public record DeclaredFieldRequest(@NotNull Integer farmlandId, @NotBlank String crop) {
    }

    public record DirectPaymentSubmitRequest(@NotEmpty List<@Valid DeclaredFieldRequest> fields) {
    }

    // ------------------------------------------------------------------------------------------ B2 views

    public record GrantObjectView(String vehicleUniqueId, double value, Long soldGameTime, Long repayment) {
    }

    public record GrantView(Long id, String kind, String status, long plannedSum, long appliedGameTime,
                            long approvalDueGameTime, Long approvedGameTime, Long purchaseDeadlineGameTime,
                            long recognisedSum, Long grantAmount, Long paidGameTime, Long bindingEndsGameTime,
                            long repaidAmount, List<GrantObjectView> objects) {
    }

    public record GrantStatusView(boolean enabled, long minSum, double grantPercent, long grantMax, int purchaseMonths,
                                  int bindingMonths, double processingDays, List<GrantView> grants) {
    }

    public record GrantRequest(@NotBlank String kind, @NotNull @Positive Long plannedSum) {
    }

    // ------------------------------------------------------------------------------------------ B4 views

    public record DiseaseView(Long id, String diseaseKey, List<String> animalTypes, String status, long declaredGameTime,
                              long endsGameTime, Long liftedGameTime) {
    }

    public record DiseaseStatusView(boolean possible, List<DiseaseView> diseases) {
    }

    private final SavegameContext context;
    private final DirectPaymentService directPayment;
    private final InvestmentGrantService grants;
    private final AnimalDiseaseService diseases;
    private final BurdeningEvents burden;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public AuthorityProgramController(SavegameContext context, DirectPaymentService directPayment,
                                      InvestmentGrantService grants, AnimalDiseaseService diseases,
                                      BurdeningEvents burden, RpsimProperties props, GameTime gameTime) {
        this.context = context;
        this.directPayment = directPayment;
        this.grants = grants;
        this.diseases = diseases;
        this.burden = burden;
        this.props = props;
        this.gameTime = gameTime;
    }

    // ------------------------------------------------------------------------------------------ B1

    @GetMapping("/api/direct-payment")
    @Transactional(readOnly = true)
    public DirectPaymentStatusView directPayment() {
        Savegame sg = context.requireActive();
        RpsimProperties.DirectPayment cfg = props.getFormulas().getDirectPayment();
        boolean open = directPayment.list(sg).stream().anyMatch(a -> DirectPaymentApplication.OPEN.equals(a.getStatus()));
        return new DirectPaymentStatusView(cfg.isEnabled(), cfg.getPremiumPerHa(), cfg.getLateCutPerDay() * 100,
                cfg.getLateMaxDays(), directPayment.crops(sg),
                open ? directPayment.form(sg).stream().map(f -> new DirectPaymentFormFieldView(f.farmlandId(), f.fieldName(),
                        f.hectares(), f.suggestedCrop())).toList() : List.of(),
                directPayment.list(sg).stream().map(this::view).toList());
    }

    @PostMapping("/api/direct-payment/{id}/submit")
    @Transactional
    public DirectPaymentView submit(@PathVariable Long id, @Valid @RequestBody DirectPaymentSubmitRequest r) {
        Savegame sg = context.requireActive();
        return view(directPayment.submit(sg, id, r.fields().stream()
                .map(f -> new DirectPaymentService.Declared(f.farmlandId(), f.crop())).toList()));
    }

    private DirectPaymentView view(DirectPaymentApplication a) {
        return new DirectPaymentView(a.getId(), a.getCropYear(), a.getStatus(), a.getOpenedGameTime(), a.getDeadlineGameTime(),
                directPayment.lateLimit(a), a.getSubmittedGameTime(), a.getLateDays(), a.getCheckStatus(),
                a.getDeviatingHectares(), a.getDeviationCut(), a.getRotationCut(), a.getLateCut(), a.getPremium(),
                a.getPaidAmount(), directPayment.fields(a).stream().map(f -> new DirectPaymentFieldView(f.getFarmlandId(),
                        f.getFieldName(), f.getHectares(), f.getDeclaredCrop(), f.getActualCrop(), f.isRotationRepeat()))
                .toList());
    }

    // ------------------------------------------------------------------------------------------ B2

    @GetMapping("/api/investment-grants")
    @Transactional(readOnly = true)
    public GrantStatusView investmentGrants() {
        Savegame sg = context.requireActive();
        RpsimProperties.InvestmentGrant cfg = props.getFormulas().getInvestmentGrant();
        return new GrantStatusView(cfg.isEnabled(), cfg.getMinSum(), cfg.getGrantShare() * 100, cfg.getGrantMax(),
                cfg.getPurchaseMonths(), cfg.getBindingMonths(), Math.round(grants.processingDays(sg) * 10) / 10.0,
                grants.list(sg).stream().map(this::view).toList());
    }

    @PostMapping("/api/investment-grants")
    @Transactional
    public GrantView applyGrant(@Valid @RequestBody GrantRequest r) {
        return view(grants.apply(context.requireActive(), r.kind(), r.plannedSum()));
    }

    @PostMapping("/api/investment-grants/{id}/proof")
    @Transactional
    public GrantView proof(@PathVariable Long id) {
        return view(grants.submitProof(context.requireActive(), id));
    }

    private GrantView view(InvestmentGrant g) {
        return new GrantView(g.getId(), g.getKind(), g.getStatus(), g.getPlannedSum(), g.getAppliedGameTime(),
                g.getApprovalDueGameTime(), g.getApprovedGameTime(), g.getPurchaseDeadlineGameTime(), g.getRecognisedSum(),
                g.getGrantAmount(), g.getPaidGameTime(), g.getBindingEndsGameTime(), g.getRepaidAmount(),
                grants.objects(g).stream().map(o -> new GrantObjectView(o.getVehicleUniqueId(), o.getVehicleValue(),
                        o.getSoldGameTime(), o.getRepayment())).toList());
    }

    // ------------------------------------------------------------------------------------------ B4

    @GetMapping("/api/animal-diseases")
    @Transactional(readOnly = true)
    public DiseaseStatusView animalDiseases() {
        Savegame sg = context.requireActive();
        boolean possible = props.getFormulas().getAnimalDisease().isEnabled()
                && burden.on(sg, BurdeningEvents.Burden.DISEASE);
        return new DiseaseStatusView(possible, diseases.list(sg).stream().map(d -> view(sg, d)).toList());
    }

    private DiseaseView view(Savegame sg, AnimalDisease d) {
        return new DiseaseView(d.getId(), d.getDiseaseKey(), d.types(), d.getStatus(), d.getDeclaredGameTime(),
                gameTime.monthStart(sg, d.getEndsMonthIndex()), d.getLiftedGameTime());
    }
}
