package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.ChannelRequest;
import de.farmpulse.rpsim.api.Requests.InsuranceOfferRequest;
import de.farmpulse.rpsim.api.Requests.OfferRequest;
import de.farmpulse.rpsim.api.Views.CaseView;
import de.farmpulse.rpsim.api.Views.ContractView;
import de.farmpulse.rpsim.api.Views.InsuranceQuoteView;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.HuntingService;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.contract.LeaseService;
import de.farmpulse.rpsim.contract.LivestockService;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
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
    private final HuntingService hunting;
    private final LivestockService livestock;
    private final LeaseService lease;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public ContractController(SavegameContext context, ContractRepository contracts, ServiceCaseRepository cases,
                              InsuranceService insurance, HuntingService hunting, LivestockService livestock, LeaseService lease,
                              ApiMapper mapper,
                              RpsimProperties props) {
        this.context = context;
        this.contracts = contracts;
        this.cases = cases;
        this.insurance = insurance;
        this.hunting = hunting;
        this.livestock = livestock;
        this.lease = lease;
        this.mapper = mapper;
        this.props = props;
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
        return context.findActive().map(sg -> cases.findBySavegameOrderByIdDesc(sg).stream().map(this::view).toList())
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
        Savegame sg = context.requireActive();
        return view(switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.accept(sg, id);
            case LEASE -> lease.accept(sg, id);
            default -> throw unsupported();
        });
    }

    @PostMapping("/api/contracts/{id}/decline")
    @Transactional
    public ContractView decline(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.decline(sg, id);
            case LEASE -> lease.decline(sg, id);
            default -> throw unsupported();
        });
    }

    @PostMapping("/api/contracts/{id}/cancel")
    @Transactional
    public ContractView cancel(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.cancel(sg, id);
            case LEASE -> lease.cancel(sg, id);
            default -> throw unsupported();
        });
    }

    /** TODO T-22: renew a lease at the rent offered one month before the end. */
    @PostMapping("/api/contracts/{id}/renew")
    @Transactional
    public ContractView renew(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (contract(sg, id).getKind()) {
            case LEASE -> lease.renew(sg, id);
            default -> throw unsupported();
        });
    }

    /** TODO T-22: buy the leased field at the owner's offer. */
    @PostMapping("/api/contracts/{id}/buy")
    @Transactional
    public ContractView buy(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (contract(sg, id).getKind()) {
            case LEASE -> lease.buy(sg, id);
            default -> throw unsupported();
        });
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
        Savegame sg = context.requireActive();
        return view(switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.accept(sg, id);
            case LIVESTOCK_OFFER -> livestock.accept(sg, id);
            default -> throw unsupported();
        });
    }

    @PostMapping("/api/cases/{id}/counter")
    @Transactional
    public CaseView counterCase(@PathVariable Long id, @Valid @RequestBody OfferRequest r) {
        Savegame sg = context.requireActive();
        return view(switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.counter(sg, id, r.amount());
            default -> throw unsupported();
        });
    }

    @PostMapping("/api/cases/{id}/measure")
    @Transactional
    public CaseView measureCase(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.measure(sg, id);
            default -> throw unsupported();
        });
    }

    @PostMapping("/api/cases/{id}/decline")
    @Transactional
    public CaseView declineCase(@PathVariable Long id) {
        Savegame sg = context.requireActive();
        return view(switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.decline(sg, id);
            case LIVESTOCK_OFFER -> livestock.decline(sg, id);
            default -> throw unsupported();
        });
    }

    private ServiceCase serviceCase(Savegame sg, Long id) {
        return cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
    }

    private static BusinessRuleException unsupported() {
        return new BusinessRuleException("UNSUPPORTED_ACTION", "Diese Aktion ist für diesen Vertrag nicht möglich.");
    }

    private Contract contract(Savegame sg, Long id) {
        return contracts.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    ContractView view(Contract c) {
        return new ContractView(c.getId(), c.getKind().name(), c.getStatus().name(), mapper.ref(c.getCharacter()), c.getLevel(),
                c.getFarmlandId(), c.getMonthlyAmount(),
                c.getCoverageRate() == null ? null : (int) Math.round(c.getCoverageRate() * 100), c.getDeductible(),
                c.getTermMonths(), c.getStartedAtGameTime(), c.getEndsAtGameTime(), c.getNextDueGameTime(),
                c.getOfferExpiresAtGameTime(), c.getMissedPayments(), c.isPaymentOverdue(), c.getEndReason(),
                c.getRenewalAmount(), c.getPurchasePrice());
    }

    CaseView view(ServiceCase s) {
        return new CaseView(s.getId(), s.getKind().name(), s.getStatus().name(), mapper.ref(s.getCharacter()), s.getFarmlandId(),
                s.getHectares(), s.getDamageAmount(), s.getPayoutAmount(), s.getCostAmount(), s.getOfferAmount(),
                s.getRoundsUsed(), s.isMeasureAgreed(), s.getReference(), s.getGameTime(), s.getDeadlineGameTime(),
                s.getResolution(), s.getKind() == de.farmpulse.rpsim.domain.CaseKind.WILDLIFE_DAMAGE
                        ? props.getFormulas().getHunting().getMeasureCost() : null,
                s.getQuantity(), s.getDirection(), s.getBaselineCount());
    }
}
