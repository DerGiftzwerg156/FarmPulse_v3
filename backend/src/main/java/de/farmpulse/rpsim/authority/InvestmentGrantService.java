package de.farmpulse.rpsim.authority;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InvestmentGrant;
import de.farmpulse.rpsim.domain.InvestmentGrantObject;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.employee.OfficeClerkService;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.InvestmentGrantObjectRepository;
import de.farmpulse.rpsim.repository.InvestmentGrantRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.VehicleDealRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B2: investment grant (owner decisions 2026-10-05 in QUESTIONS.md).
 * <ul>
 *   <li>Application under Ämter: kind (BUILDING or MACHINE) and planned sum (min-sum); at most max-open-per-kind open
 *   applications per kind. Approved after processing-days, with an office clerk x (1 - clerk-reduction-max x effective
 *   skill / 100); the purchase must follow within purchase-months.</li>
 *   <li>Proof from the booking journal of the mod (month sums per money type): after the approval every rise of
 *   SHOP_PROPERTY_BUY (building) or SHOP_VEHICLE_BUY (machine) between two exports counts - a purchase before the
 *   approval does not (early start of the measure). Used machines of the neighbours (R3-V), leasing and borrowed
 *   machines are other money types and do not count.</li>
 *   <li>Grant = grant-share x min(recognised, planned sum), at most grant-max, as INVESTMENT_GRANT - by button
 *   "Nachweis einreichen" or automatically at the end of the deadline; nothing bought = expired.</li>
 *   <li>Binding (machines only): vehicles that appear in {@code assets.vehicles} after the approval are the funded
 *   objects. One that disappears together with a rise of SHOP_VEHICLE_SELL within binding-months after the payment
 *   is repaid pro rata: grant x (its value / value of all funded objects) x (remaining months / binding-months), as a
 *   bill GRANT_REPAYMENT like a tax bill (AuthorityBillService). A funded machine sold before the payment lowers the
 *   recognised sum by its value.</li>
 * </ul>
 */
@Service
public class InvestmentGrantService {

    public static final String RELATED = "INVESTMENT_GRANT";
    static final String BUY_PROPERTY = "SHOP_PROPERTY_BUY";
    static final String BUY_VEHICLE = "SHOP_VEHICLE_BUY";
    static final String SELL_VEHICLE = "SHOP_VEHICLE_SELL";

    private final SavegameRepository savegames;
    private final InvestmentGrantRepository grants;
    private final InvestmentGrantObjectRepository objects;
    private final VehicleDealRepository deals;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final LoanedVehicles loaned;
    private final OfficeClerkService clerks;
    private final SatisfactionService satisfaction;
    private final AuthorityBillService bills;
    private final ServiceRoleService roles;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public InvestmentGrantService(SavegameRepository savegames, InvestmentGrantRepository grants,
                                  InvestmentGrantObjectRepository objects, VehicleDealRepository deals, FactsService facts,
                                  FinanceJournalService journal, LoanedVehicles loaned, OfficeClerkService clerks,
                                  SatisfactionService satisfaction, AuthorityBillService bills, ServiceRoleService roles,
                                  OutboxService outbox, NarrationRequestService narration, DiaryService diary,
                                  RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.grants = grants;
        this.objects = objects;
        this.deals = deals;
        this.facts = facts;
        this.journal = journal;
        this.loaned = loaned;
        this.clerks = clerks;
        this.satisfaction = satisfaction;
        this.bills = bills;
        this.roles = roles;
        this.outbox = outbox;
        this.narration = narration;
        this.diary = diary;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.InvestmentGrant cfg() {
        return props.getFormulas().getInvestmentGrant();
    }

    public List<InvestmentGrant> list(Savegame sg) {
        return grants.findBySavegameOrderByIdDesc(sg);
    }

    public List<InvestmentGrantObject> objects(InvestmentGrant g) {
        return objects.findByGrantIdOrderByIdAsc(g.getId());
    }

    static String buyType(InvestmentGrant g) {
        return InvestmentGrant.BUILDING.equals(g.getKind()) ? BUY_PROPERTY : BUY_VEHICLE;
    }

    /** Processing time: processing-days x (1 - clerk-reduction-max x effective skill / 100) with the best clerk. */
    public double processingDays(Savegame sg) {
        double skill = clerks.bestClerk(sg).map(e -> satisfaction.needs(e).effectiveSkill()).orElse(0.0);
        return cfg().getProcessingDays() * (1 - cfg().getClerkReductionMax() * Math.max(0, Math.min(100, skill)) / 100.0);
    }

    /** Grant = grant-share x min(recognised, planned), at most grant-max. */
    public static long grant(long recognised, long planned, RpsimProperties.InvestmentGrant cfg) {
        return Math.min(cfg.getGrantMax(), Math.round(cfg.getGrantShare() * Math.max(0, Math.min(recognised, planned))));
    }

    /** Repayment of a sold funded machine: grant x value share x remaining share of the binding period. */
    public static long repayment(long grant, double value, double totalValue, double remainingMonths, int bindingMonths) {
        if (totalValue <= 0 || bindingMonths <= 0 || remainingMonths <= 0) {
            return 0;
        }
        return Math.round(grant * Math.min(1, value / totalValue) * Math.min(1, remainingMonths / bindingMonths));
    }

    // ------------------------------------------------------------------------------------------ application

    @Transactional
    public InvestmentGrant apply(Savegame sg, String kind, long plannedSum) {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("GRANT_DISABLED", "Die Investitionsförderung ist abgeschaltet.");
        }
        if (!InvestmentGrant.BUILDING.equals(kind) && !InvestmentGrant.MACHINE.equals(kind)) {
            throw new BusinessRuleException("GRANT_KIND", "Bitte Stall/Gebäude oder Maschine wählen.");
        }
        if (plannedSum < cfg().getMinSum()) {
            throw new BusinessRuleException("GRANT_MIN_SUM", "Gefördert werden Vorhaben ab " + cfg().getMinSum() + " €.");
        }
        long open = list(sg).stream().filter(g -> kind.equals(g.getKind()) && (InvestmentGrant.APPLIED.equals(g.getStatus())
                || InvestmentGrant.APPROVED.equals(g.getStatus()))).count();
        if (open >= cfg().getMaxOpenPerKind()) {
            throw new BusinessRuleException("GRANT_OPEN", "Für diese Art läuft schon ein Förderantrag.");
        }
        long now = sg.getCurrentGameTime();
        InvestmentGrant g = new InvestmentGrant();
        g.setSavegame(sg);
        g.setCharacter(roles.ensure(sg, CharacterRole.AUTHORITY));
        g.setKind(kind);
        g.setStatus(InvestmentGrant.APPLIED);
        g.setPlannedSum(plannedSum);
        g.setAppliedGameTime(now);
        g.setApprovalDueGameTime(now + GameTime.days(processingDays(sg)));
        grants.save(g);
        diary.addAuto(sg, "OTHER", "Förderantrag gestellt", (InvestmentGrant.BUILDING.equals(kind) ? "Stall/Gebäude"
                : "Maschine") + ", geplant " + plannedSum + " €.", RELATED, g.getId());
        return g;
    }

    /** "Nachweis einreichen": paid at once with what was bought so far. */
    @Transactional
    public InvestmentGrant submitProof(Savegame sg, Long id) {
        InvestmentGrant g = own(sg, id);
        if (!InvestmentGrant.APPROVED.equals(g.getStatus())) {
            throw new BusinessRuleException("GRANT_NOT_APPROVED", "Dieser Antrag ist nicht (mehr) bewilligt.");
        }
        if (recognised(g) <= 0) {
            throw new BusinessRuleException("GRANT_NOTHING_BOUGHT", "Seit der Bewilligung wurde noch nichts gekauft.");
        }
        finish(sg, g);
        return g;
    }

    // ------------------------------------------------------------------------------------------ daily

    @EventListener
    @Order(81)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (InvestmentGrant g : list(sg)) {
            if (InvestmentGrant.APPLIED.equals(g.getStatus()) && g.getApprovalDueGameTime() <= now) {
                approve(sg, g);
            } else if (InvestmentGrant.APPROVED.equals(g.getStatus()) && g.getPurchaseDeadlineGameTime() != null
                    && g.getPurchaseDeadlineGameTime() < now) {
                finish(sg, g);
            }
        }
    }

    void approve(Savegame sg, InvestmentGrant g) {
        long now = sg.getCurrentGameTime();
        FarmFacts f = facts.latest(sg).orElse(null);
        g.setStatus(InvestmentGrant.APPROVED);
        g.setApprovedGameTime(now);
        g.setPurchaseDeadlineGameTime(gameTime.addMonths(sg, now, cfg().getPurchaseMonths()));
        Baseline b = baseline(f, buyType(g));
        g.setBuyMonthKey(b.key());
        g.setBuyMonthAmount(b.amount());
        Baseline sell = baseline(f, SELL_VEHICLE);
        g.setSellMonthKey(sell.key());
        g.setSellMonthAmount(sell.amount());
        g.setBaselineVehicles(String.join(",", vehicleIds(sg, f)));
        narration.request(sg, NarrationEventType.INVESTMENT_GRANT_APPROVED).from(g.getCharacter())
                .facts(NarrationFacts.builder().put("kind", g.getKind()).put("plannedSum", g.getPlannedSum())
                        .put("grantPercent", Math.round(cfg().getGrantShare() * 100)).put("grantMax", cfg().getGrantMax())
                        .put("purchaseMonths", cfg().getPurchaseMonths()).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, g.getId()).formLink("/aemter?grant=" + g.getId())
                .submit();
        diary.addAuto(sg, "OTHER", "Förderantrag bewilligt", "Jetzt innerhalb von " + cfg().getPurchaseMonths()
                + " Monaten kaufen – Käufe vor der Bewilligung zählen nicht.", RELATED, g.getId());
    }

    /** Recognised sum minus the value of funded machines sold before the payment. */
    long recognised(InvestmentGrant g) {
        double sold = objects(g).stream().filter(o -> o.getSoldGameTime() != null).mapToDouble(InvestmentGrantObject::getVehicleValue)
                .sum();
        return Math.max(0, g.getRecognisedSum() - Math.round(sold));
    }

    void finish(Savegame sg, InvestmentGrant g) {
        long now = sg.getCurrentGameTime();
        long recognised = recognised(g);
        long amount = grant(recognised, g.getPlannedSum(), cfg());
        g.setClosedGameTime(now);
        if (amount <= 0) {
            g.setStatus(InvestmentGrant.EXPIRED);
            narration.request(sg, NarrationEventType.INVESTMENT_GRANT_EXPIRED).from(g.getCharacter())
                    .facts(NarrationFacts.builder().put("kind", g.getKind()).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, g.getId()).submit();
            diary.addAuto(sg, "OTHER", "Förderung verfallen", "Bis zum Fristende wurde nichts gekauft.", RELATED, g.getId());
            return;
        }
        g.setStatus(InvestmentGrant.PAID);
        g.setGrantAmount(amount);
        g.setPaidGameTime(now);
        g.setBindingEndsGameTime(gameTime.addMonths(sg, now, cfg().getBindingMonths()));
        outbox.money(sg, amount, MoneyReason.INVESTMENT_GRANT, "Investitionsförderung", new Related(RELATED, g.getId()));
        boolean bound = InvestmentGrant.MACHINE.equals(g.getKind());
        narration.request(sg, NarrationEventType.INVESTMENT_GRANT_PAID).from(g.getCharacter())
                .facts(NarrationFacts.builder().put("kind", g.getKind()).put("recognised", recognised).put("grant", amount)
                        .put("bindingNote", bound ? "Bitte beachten Sie: Wer die geförderten Maschinen innerhalb von "
                                + cfg().getBindingMonths() + " Monaten verkauft, muss anteilig zurückzahlen. " : "")
                        .build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, g.getId()).submit();
        diary.addAuto(sg, "MARKET", "Investitionsförderung erhalten", amount + " € auf " + recognised
                + " € anerkannte Kosten.", RELATED, g.getId());
    }

    // ------------------------------------------------------------------------------------------ exports

    /** Every export: purchases after the approval, new machines, sales of funded machines in the binding period. */
    @EventListener
    @Order(86)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null) {
            return;
        }
        long now = e.gameTime();
        for (InvestmentGrant g : list(sg)) {
            if (InvestmentGrant.APPROVED.equals(g.getStatus())) {
                track(sg, g, f, now);
            } else if (InvestmentGrant.PAID.equals(g.getStatus()) && InvestmentGrant.MACHINE.equals(g.getKind())
                    && g.getBindingEndsGameTime() != null && now < g.getBindingEndsGameTime()) {
                sales(sg, g, f, now);
            }
        }
    }

    void track(Savegame sg, InvestmentGrant g, FarmFacts f, long now) {
        if (g.getPurchaseDeadlineGameTime() != null && now > g.getPurchaseDeadlineGameTime()) {
            return;
        }
        Rise rise = rise(f, buyType(g), g.getBuyMonthKey(), g.getBuyMonthAmount());
        if (rise != null) {
            g.setRecognisedSum(g.getRecognisedSum() + Math.round(rise.delta()));
            g.setBuyMonthKey(rise.key());
            g.setBuyMonthAmount(rise.amount());
        }
        if (!InvestmentGrant.MACHINE.equals(g.getKind())) {
            return;
        }
        Set<String> known = new HashSet<>(baseline(g));
        objects(g).forEach(o -> known.add(o.getVehicleUniqueId()));
        Set<String> bought = usedPurchases(sg);
        for (BridgeDtos.Vehicle v : ownVehicles(sg, f)) {
            if (known.contains(v.uniqueId()) || bought.contains(v.uniqueId())) {
                continue;
            }
            InvestmentGrantObject o = new InvestmentGrantObject();
            o.setSavegame(sg);
            o.setGrantId(g.getId());
            o.setVehicleUniqueId(v.uniqueId());
            o.setVehicleValue(v.value() == null ? 0 : v.value());
            o.setSeenGameTime(now);
            objects.save(o);
        }
        // a funded machine sold before the payment lowers the recognised sum (see recognised())
        Rise sell = rise(f, SELL_VEHICLE, g.getSellMonthKey(), g.getSellMonthAmount());
        if (sell != null) {
            g.setSellMonthKey(sell.key());
            g.setSellMonthAmount(sell.amount());
            if (sell.delta() > 0) {
                Set<String> present = ids(ownVehicles(sg, f));
                objects(g).stream().filter(o -> o.getSoldGameTime() == null && !present.contains(o.getVehicleUniqueId()))
                        .forEach(o -> o.setSoldGameTime(now));
            }
        }
    }

    void sales(Savegame sg, InvestmentGrant g, FarmFacts f, long now) {
        Rise sell = rise(f, SELL_VEHICLE, g.getSellMonthKey(), g.getSellMonthAmount());
        if (sell == null) {
            return;
        }
        g.setSellMonthKey(sell.key());
        g.setSellMonthAmount(sell.amount());
        if (sell.delta() <= 0) {
            return;
        }
        Set<String> present = ids(ownVehicles(sg, f));
        List<InvestmentGrantObject> all = objects(g);
        double total = all.stream().filter(o -> o.getSoldGameTime() == null || o.getRepayment() != null)
                .mapToDouble(InvestmentGrantObject::getVehicleValue).sum();
        double remaining = (g.getBindingEndsGameTime() - now) / (double) gameTime.msPerMonth(sg);
        for (InvestmentGrantObject o : all) {
            if (o.getSoldGameTime() != null || present.contains(o.getVehicleUniqueId())) {
                continue;
            }
            long amount = repayment(g.getGrantAmount(), o.getVehicleValue(), total, remaining, cfg().getBindingMonths());
            o.setSoldGameTime(now);
            o.setRepayment(amount);
            if (amount <= 0) {
                continue;
            }
            ServiceCase bill = bills.create(sg, CaseKind.GRANT_REPAYMENT, g.getCharacter(),
                    "Rückforderung Investitionsförderung", amount, String.valueOf(g.getId()));
            o.setRepaymentCaseId(bill.getId());
            g.setRepaidAmount(g.getRepaidAmount() + amount);
            narration.request(sg, NarrationEventType.INVESTMENT_GRANT_REPAYMENT).from(g.getCharacter())
                    .facts(NarrationFacts.builder().put("amount", amount).put("remainingMonths", Math.max(0, Math.round(remaining)))
                            .put("bindingMonths", cfg().getBindingMonths())
                            .put("paymentDays", Math.round(props.getFormulas().getTax().getPaymentDays())).build())
                    .category(CommunicationCategory.CONTRACT).related(AuthorityBillService.RELATED, bill.getId())
                    .formLink("/aemter?case=" + bill.getId()).submit();
            diary.addAuto(sg, "OTHER", "Förderung anteilig zurückgefordert", "Geförderte Maschine in der Bindungsfrist "
                    + "verkauft: " + amount + " € zurück an das Amt.", RELATED, g.getId());
        }
    }

    // ------------------------------------------------------------------------------------------ journal and vehicles

    /** Month key and absolute sum of a money type of the latest journal month. */
    record Baseline(Long key, Double amount) {
    }

    Baseline baseline(FarmFacts f, String type) {
        List<FinanceJournalService.Month> months = journal.months(f);
        if (months.isEmpty()) {
            return new Baseline(null, null);
        }
        FinanceJournalService.Month last = months.getLast();
        return new Baseline(last.key(), Math.abs(last.amountOf(type)));
    }

    /** Rise of a money type since the last seen month / sum; null without journal (nothing known yet). */
    record Rise(double delta, long key, double amount) {
    }

    Rise rise(FarmFacts f, String type, Long lastKey, Double lastAmount) {
        List<FinanceJournalService.Month> months = journal.months(f);
        if (months.isEmpty()) {
            return null;
        }
        FinanceJournalService.Month latest = months.getLast();
        if (lastKey == null) {
            return new Rise(0, latest.key(), Math.abs(latest.amountOf(type))); // first journal seen: baseline only
        }
        double delta = 0;
        for (FinanceJournalService.Month m : months) {
            if (m.key() < lastKey) {
                continue;
            }
            double abs = Math.abs(m.amountOf(type));
            delta += m.key() == lastKey ? Math.max(0, abs - (lastAmount == null ? 0 : lastAmount)) : abs;
        }
        if (latest.key() < lastKey) {
            return new Rise(0, lastKey, lastAmount == null ? 0 : lastAmount); // an older save was loaded
        }
        return new Rise(delta, latest.key(), Math.abs(latest.amountOf(type)));
    }

    List<BridgeDtos.Vehicle> ownVehicles(Savegame sg, FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return List.of();
        }
        return loaned.own(sg, f.assets().vehicles()).stream().filter(v -> v != null && v.uniqueId() != null).toList();
    }

    static Set<String> ids(List<BridgeDtos.Vehicle> vehicles) {
        return vehicles.stream().map(BridgeDtos.Vehicle::uniqueId).collect(Collectors.toSet());
    }

    List<String> vehicleIds(Savegame sg, FarmFacts f) {
        return ownVehicles(sg, f).stream().map(BridgeDtos.Vehicle::uniqueId).sorted().toList();
    }

    static List<String> baseline(InvestmentGrant g) {
        return g.getBaselineVehicles() == null || g.getBaselineVehicles().isBlank() ? List.of()
                : Arrays.stream(g.getBaselineVehicles().split(",")).map(String::strip).toList();
    }

    /** Vehicles delivered by a used-machine deal (R3-V) - another money type, not funded. */
    Set<String> usedPurchases(Savegame sg) {
        return deals.findBySavegameOrderByIdDesc(sg).stream().map(VehicleDeal::getVehicleId).filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    private InvestmentGrant own(Savegame sg, Long id) {
        return grants.findById(id).filter(g -> g.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("grant " + id));
    }
}
