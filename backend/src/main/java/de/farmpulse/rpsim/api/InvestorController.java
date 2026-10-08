package de.farmpulse.rpsim.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.farmpulse.rpsim.api.Views.CaseView;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.InvestorPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.investor.InvestorOfferService;
import de.farmpulse.rpsim.investor.InvestorService;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3.2 R32-I: app "Bank", area "Investoren" - the open offer with its packages side by side, the contracts
 * with their considerations, the current period, deliveries, payments and P1 consents. Declining an offer, paying a
 * claim and answering the P2 / P4 requests go through {@code /api/cases/{id}/accept|decline}.
 */
@RestController
public class InvestorController {

    /** One consideration with its current period (null outside the term) and what is still open to deliver. */
    public record ObligationView(Long id, String type, boolean main, String fillType, String subType, Long quantity,
                                 Long minPerYear, Double hectares, Double rate, Double target, String targetKind,
                                 Double unitPrice, long valuePerYear, long totalValue, long deliveredTotal,
                                 List<Integer> consents, PeriodView current, long outstanding,
                                 List<PeriodView> breaches) {
    }

    /** {@code required} / {@code delivered}: litres, animals or hectares x 100 (A3). */
    public record PeriodView(long periodKey, boolean monthly, int year, Long required, long delivered, String status,
                             Long graceUntil, Long compensation, Long checkedGameTime) {
    }

    public record PaymentView(Long id, String kind, long amount, Integer year, long gameTime, String status,
                              Long claimCaseId, String note) {
    }

    public record ContractView(Long id, Long caseId, Long characterId, String investor, String kind, String kindLabel,
                               int packageNo, long amount, String capitalType, int years, int startYear, int endYear,
                               double targetReturn, long targetValue, String status, int breaches, boolean announced,
                               boolean repaymentDue, Long extensionOf, Long extendedBy, String endReason,
                               List<ObligationView> considerations, List<PaymentView> payments) {
    }

    public record OfferView(CaseView offer, boolean extension, List<ContractView> packages) {
    }

    public record MilkView(String fillType, double amount) {
    }

    public record SubTypeView(String name, int count) {
    }

    public record StallView(String husbandryUniqueId, String animalType, List<MilkView> milk, List<SubTypeView> subTypes) {
    }

    public record FieldView(int farmlandId, String name, Double hectares) {
    }

    public record InvestorsView(boolean enabled, boolean savegameEnabled, int maxActive, int running,
                                int breachesToTerminate, double graceDays, double compensationMarkup,
                                List<OfferView> offers, List<ContractView> contracts, List<CaseView> cases,
                                List<StallView> stalls, List<FieldView> fields) {
    }

    public record DeliverRequest(@NotNull @Positive Long quantity, String husbandryUniqueId) {
    }

    public record ConsentRequest(@NotNull Integer farmlandId) {
    }

    private final SavegameContext context;
    private final InvestorOfferService offers;
    private final InvestorService investors;
    private final ServiceCaseRepository cases;
    private final FactsService facts;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public InvestorController(SavegameContext context, InvestorOfferService offers, InvestorService investors,
                              ServiceCaseRepository cases, FactsService facts, ApiMapper mapper, RpsimProperties props) {
        this.context = context;
        this.offers = offers;
        this.investors = investors;
        this.cases = cases;
        this.facts = facts;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping("/api/investors")
    @Transactional(readOnly = true)
    public InvestorsView investors() {
        Savegame sg = context.requireActive();
        RpsimProperties.Investor cfg = props.getFormulas().getInvestor();
        FarmFacts f = facts.latest(sg).orElse(null);
        List<OfferView> open = new ArrayList<>();
        for (ServiceCase sc : offers.offers(sg)) {
            if (sc.getStatus() == de.farmpulse.rpsim.domain.CaseStatus.AWAITING_PLAYER) {
                open.add(new OfferView(mapper.serviceCase(sc), sc.getExternalId() != null,
                        offers.packagesOf(sc).stream().map(c -> view(sg, c)).toList()));
            }
        }
        List<CaseView> other = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.INVESTOR_REMINDER,
                CaseKind.INVESTOR_CLAIM, CaseKind.INVESTOR_PURCHASE, CaseKind.INVESTOR_VISIT)).stream()
                .map(mapper::serviceCase).toList();
        return new InvestorsView(cfg.isEnabled(), sg.isInvestorsEnabled(), cfg.getMaxActive(), offers.runningInvestors(sg),
                cfg.getBreachesToTerminate(), cfg.getGraceDays(), cfg.getCompensationMarkup(), open,
                investors.contracts(sg).stream().map(c -> view(sg, c)).toList(), other, stalls(f), fields(f));
    }

    ContractView view(Savegame sg, InvestorContract c) {
        boolean running = c.getAcceptedGameTime() != null;
        List<ObligationView> obs = investors.obligationsOf(c).stream().map(o -> view(sg, o, running)).toList();
        List<PaymentView> ps = investors.paymentsOf(c).stream().map(InvestorController::view).toList();
        return new ContractView(c.getId(), c.getCaseId(), c.getCharacter() == null ? null : c.getCharacter().getId(),
                c.getCharacter() == null ? null : c.getCharacter().getName(), c.getKind(), offers.kindLabel(c.getKind()),
                c.getPackageNo(), c.getAmount(), c.getCapitalType(), c.getYears(), c.getStartYear(), c.endYear(),
                c.getTargetReturn(), c.getTargetValue(), c.getStatus(), c.getBreaches(), c.isAnnounced(),
                c.isRepaymentDue(), c.getExtensionOf(), c.getExtendedBy(), c.getEndReason(), obs, ps);
    }

    private ObligationView view(Savegame sg, InvestorObligation o, boolean running) {
        InvestorPeriod cur = running && InvestorContract.ACTIVE.equals(o.getContract().getStatus())
                ? investors.currentPeriod(sg, o) : null;
        List<Integer> consents = o.getConsents() == null || o.getConsents().isBlank() ? List.of()
                : Arrays.stream(o.getConsents().split(",")).map(String::strip).map(Integer::valueOf).toList();
        List<PeriodView> breaches = running ? investors.periodsOf(o).stream().filter(InvestorPeriod::isBreach)
                .map(InvestorController::view).toList() : List.of();
        long open = running && de.farmpulse.rpsim.investor.InvestorConsideration.DELIVERED
                .contains(de.farmpulse.rpsim.investor.InvestorConsideration.of(o.getType()))
                ? investors.outstanding(sg, o) : 0;
        return new ObligationView(o.getId(), o.getType(), o.isMain(), o.getFillType(), o.getSubType(), o.getQuantity(),
                o.getMinPerYear(), o.getHectares(), o.getRate(), o.getTarget(), o.getTargetKind(), o.getUnitPrice(),
                o.getValuePerYear(), o.getTotalValue(), o.getDeliveredTotal(), consents, cur == null ? null : view(cur),
                open, breaches);
    }

    private static PeriodView view(InvestorPeriod p) {
        return new PeriodView(p.getPeriodKey(), p.isMonthly(), p.getPeriodYear(), p.getRequired(), p.getDelivered(),
                p.getStatus(), p.getGraceUntil(), p.getCompensation(), p.getCheckedGameTime());
    }

    private static PaymentView view(InvestorPayment p) {
        return new PaymentView(p.getId(), p.getKind(), p.getAmount(), p.getPaymentYear(), p.getGameTime(), p.getStatus(),
                p.getClaimCaseId(), p.getNote());
    }

    static List<StallView> stalls(FarmFacts f) {
        List<StallView> out = new ArrayList<>();
        if (f == null || f.husbandries() == null) {
            return out;
        }
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            if (h == null || h.husbandryUniqueId() == null) {
                continue;
            }
            String type = f.assets() == null || f.assets().animals() == null ? null : f.assets().animals().stream()
                    .filter(a -> a != null && h.husbandryUniqueId().equals(a.husbandryUniqueId())).map(BridgeDtos.Animal::type)
                    .findFirst().orElse(null);
            List<MilkView> milk = h.storage() == null ? List.of() : h.storage().stream()
                    .filter(s -> s != null && s.fillType() != null)
                    .map(s -> new MilkView(s.fillType(), s.amount() == null ? 0 : s.amount())).toList();
            List<SubTypeView> subs = h.subTypes() == null ? List.of() : h.subTypes().stream()
                    .filter(s -> s != null && s.name() != null && s.count() != null)
                    .map(s -> new SubTypeView(s.name(), s.count())).toList();
            out.add(new StallView(h.husbandryUniqueId(), type, milk, subs));
        }
        return out;
    }

    static List<FieldView> fields(FarmFacts f) {
        if (f == null || f.fields() == null) {
            return List.of();
        }
        return f.fields().stream().filter(x -> x != null && x.farmlandId() != null)
                .map(x -> new FieldView(x.farmlandId(), x.name(), x.hectares())).toList();
    }

    /** "Annehmen" of one package of the open offer. */
    @PostMapping("/api/investors/offers/{caseId}/packages/{contractId}/accept")
    @Transactional
    public ContractView accept(@PathVariable Long caseId, @PathVariable Long contractId) {
        Savegame sg = context.requireActive();
        return view(sg, offers.accept(sg, caseId, contractId));
    }

    /** "Liefern" of goods, milk or animals (partial deliveries allowed). */
    @PostMapping("/api/investors/obligations/{id}/deliver")
    @Transactional
    public DeliveryView deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest r) {
        Savegame sg = context.requireActive();
        var d = investors.deliver(sg, id, r.quantity(), r.husbandryUniqueId());
        return new DeliveryView(d.getId(), d.getQuantity(), d.getStatus());
    }

    public record DeliveryView(Long id, long quantity, String status) {
    }

    /** P1 "Zustimmung einholen" for the sale of an own field. */
    @PostMapping("/api/investors/contracts/{id}/field-consent")
    @Transactional
    public ContractView fieldConsent(@PathVariable Long id, @Valid @RequestBody ConsentRequest r) {
        Savegame sg = context.requireActive();
        investors.fieldConsent(sg, id, r.farmlandId());
        return view(sg, investors.contract(sg, id));
    }

    /** "Bezahlen" of a payment the mod refused for lack of money. */
    @PostMapping("/api/investors/payments/{id}/pay")
    @Transactional
    public PaymentView pay(@PathVariable Long id) {
        return view(investors.payOpen(context.requireActive(), id));
    }
}
