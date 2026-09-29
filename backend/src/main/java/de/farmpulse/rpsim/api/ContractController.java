package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.ChannelRequest;
import de.farmpulse.rpsim.api.Requests.InsuranceOfferRequest;
import de.farmpulse.rpsim.api.Requests.OfferRequest;
import de.farmpulse.rpsim.api.Views.CaseView;
import de.farmpulse.rpsim.api.Views.ContractView;
import de.farmpulse.rpsim.api.Views.InsuranceQuoteView;
import de.farmpulse.rpsim.club.ClubService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.contract.LeaseService;
import de.farmpulse.rpsim.contract.MaintenanceService;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.tax.TaxService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** TODO T-20 / T-22: contracts (insurance, lease, maintenance) and service cases of the service characters. */
@RestController
public class ContractController {

    private final SavegameContext context;
    private final ContractRepository contracts;
    private final ServiceCaseRepository cases;
    private final InsuranceService insurance;
    private final LeaseService lease;
    private final MaintenanceService maintenance;
    private final ApiMapper mapper;
    private final RpsimProperties props;
    private final TaxService tax;
    private final ClubService clubs;
    private final ContractActions actions;

    public ContractController(SavegameContext context, ContractRepository contracts, ServiceCaseRepository cases,
                              InsuranceService insurance, LeaseService lease, MaintenanceService maintenance,
                              ApiMapper mapper, RpsimProperties props, TaxService tax, ClubService clubs,
                              ContractActions actions) {
        this.context = context;
        this.contracts = contracts;
        this.cases = cases;
        this.insurance = insurance;
        this.lease = lease;
        this.maintenance = maintenance;
        this.mapper = mapper;
        this.props = props;
        this.tax = tax;
        this.clubs = clubs;
        this.actions = actions;
    }

    @GetMapping("/api/contracts")
    @Transactional(readOnly = true)
    public List<ContractView> contracts() {
        return context.findActive().map(sg -> contracts.findBySavegameOrderByIdDesc(sg).stream().map(this::view).toList())
                .orElse(List.of());
    }

    @GetMapping("/api/cases")
    @Transactional(readOnly = true)
    public List<CaseView> cases() {
        // contracts the contractor decided not to refer are internal bookkeeping (TODO T-22)
        return context.findActive().map(sg -> cases.findBySavegameOrderByIdDesc(sg).stream()
                        .filter(c -> !"NOT_REFERRED".equals(c.getResolution())).map(this::view).toList())
                .orElse(List.of());
    }

    @GetMapping("/api/insurance/quotes")
    @Transactional(readOnly = true)
    public List<InsuranceQuoteView> quotes() {
        Savegame sg = context.requireActive();
        return props.getFormulas().getInsurance().getLevels().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> new InsuranceQuoteView(e.getKey(), insurance.monthlyPremium(sg, e.getKey()),
                        (int) Math.round(e.getValue().getCoverageRate() * 100), e.getValue().getDeductible()))
                .toList();
    }

    @PostMapping("/api/insurance/offer")
    @Transactional
    public ContractView requestInsuranceOffer(@Valid @RequestBody InsuranceOfferRequest r) {
        return view(insurance.offer(context.requireActive(), r.level()));
    }

    @PostMapping("/api/contracts/{id}/accept")
    @Transactional
    public ContractView accept(@PathVariable Long id) {
        return view(actions.accept(context.requireActive(), id));
    }

    @PostMapping("/api/contracts/{id}/decline")
    @Transactional
    public ContractView decline(@PathVariable Long id) {
        return view(actions.decline(context.requireActive(), id));
    }

    @PostMapping("/api/contracts/{id}/cancel")
    @Transactional
    public ContractView cancel(@PathVariable Long id) {
        return view(actions.cancel(context.requireActive(), id));
    }

    /** Roadmap V2 R2-E1: ask a tax advisor for an offer. */
    @PostMapping("/api/tax/advisor/offer")
    @Transactional
    public ContractView requestTaxAdvisorOffer() {
        return view(tax.offerAdvisor(context.requireActive()));
    }

    /** Roadmap V2 R2-E4: support a club with one of the offered tiers. */
    @PostMapping("/api/cases/{id}/sponsor")
    @Transactional
    public CaseView sponsor(@PathVariable Long id, @Valid @RequestBody OfferRequest r) {
        return view(clubs.sponsor(context.requireActive(), id, r.amount()));
    }

    /** TODO T-22: ask the workshop for a maintenance contract offer. */
    @PostMapping("/api/maintenance/offer")
    @Transactional
    public ContractView requestMaintenanceOffer() {
        return view(maintenance.offer(context.requireActive()));
    }

    /** TODO T-22: renew a lease at the rent offered one month before the end. */
    @PostMapping("/api/contracts/{id}/renew")
    @Transactional
    public ContractView renew(@PathVariable Long id) {
        return view(actions.renew(context.requireActive(), id));
    }

    /** TODO T-22: buy the leased field at the owner's offer. */
    @PostMapping("/api/contracts/{id}/buy")
    @Transactional
    public ContractView buy(@PathVariable Long id) {
        return view(actions.buy(context.requireActive(), id));
    }

    /** TODO T-22: ask the owner of a field for a lease (answer: offer or refusal). */
    @PostMapping("/api/farmlands/{farmlandId}/lease-request")
    @Transactional
    public ContractView requestLease(@PathVariable int farmlandId) {
        return view(lease.requestOffer(context.requireActive(), farmlandId));
    }

    @PostMapping("/api/cases/{id}/report")
    @Transactional
    public CaseView report(@PathVariable Long id, @RequestBody(required = false) ChannelRequest r) {
        return view(insurance.report(context.requireActive(), id, r == null ? null : r.channel()));
    }

    @PostMapping("/api/cases/{id}/accept")
    @Transactional
    public CaseView acceptCase(@PathVariable Long id) {
        return view(actions.acceptCase(context.requireActive(), id));
    }

    @PostMapping("/api/cases/{id}/counter")
    @Transactional
    public CaseView counterCase(@PathVariable Long id, @Valid @RequestBody OfferRequest r) {
        return view(actions.counterCase(context.requireActive(), id, r.amount()));
    }

    @PostMapping("/api/cases/{id}/measure")
    @Transactional
    public CaseView measureCase(@PathVariable Long id) {
        return view(actions.measureCase(context.requireActive(), id));
    }

    @PostMapping("/api/cases/{id}/decline")
    @Transactional
    public CaseView declineCase(@PathVariable Long id) {
        return view(actions.declineCase(context.requireActive(), id));
    }

    ContractView view(Contract c) {
        return mapper.contract(c);
    }

    CaseView view(ServiceCase s) {
        return mapper.serviceCase(s);
    }
}
