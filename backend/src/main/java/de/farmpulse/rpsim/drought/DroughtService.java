package de.farmpulse.rpsim.drought;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.Drought;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.GrowingFieldMonth;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.RainPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.market.ForwardContractService;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.DroughtRepository;
import de.farmpulse.rpsim.repository.GrowingFieldMonthRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-W: drought (owner decisions in QUESTIONS.md). At every month start the month that just ended is rated
 * from the rain time of R2-C2: in a growth month ({@code drought.periods}) it is dry when it rained less than
 * {@code max-rain-share} of the observed time and at least {@code min-observed-share} of the month was observed; a
 * month without enough data, a wet month and a month outside the growth months end the series. The cooperative warns at
 * the first dry month (with an offer of the drought insurance when none runs); after {@code min-periods} dry months in a
 * row the drought is declared once per series: regional HARVEST_FAILURE events for the crops standing in the village,
 * village gossip, the payout of the weather-index insurance (W3) and the drought aid of the authority (W2). The yield
 * of the game does not change.
 */
@Service
public class DroughtService {

    public static final String RELATED = "DROUGHT";
    public static final String CASE_RELATED = "SERVICE_CASE";

    private final SavegameRepository savegames;
    private final DroughtRepository droughts;
    private final GrowingFieldMonthRepository growing;
    private final ServiceCaseRepository cases;
    private final FactsService facts;
    private final FieldService fields;
    private final InsuranceService insurance;
    private final MarketEventEngine market;
    private final ForwardContractService forwards;
    private final OutboxService outbox;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final FallbackTemplates labels;
    private final DiaryService diary;
    private final RandomSource random;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public DroughtService(SavegameRepository savegames, DroughtRepository droughts, GrowingFieldMonthRepository growing,
                          ServiceCaseRepository cases, FactsService facts, FieldService fields, InsuranceService insurance,
                          MarketEventEngine market, ForwardContractService forwards, OutboxService outbox,
                          CharacterLookup lookup, NarrationRequestService narration, FallbackTemplates labels,
                          DiaryService diary, RandomSource random, GameTime gameTime, RpsimProperties props) {
        this.savegames = savegames;
        this.droughts = droughts;
        this.growing = growing;
        this.cases = cases;
        this.facts = facts;
        this.fields = fields;
        this.insurance = insurance;
        this.market = market;
        this.forwards = forwards;
        this.outbox = outbox;
        this.lookup = lookup;
        this.narration = narration;
        this.labels = labels;
        this.diary = diary;
        this.random = random;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.Drought cfg() {
        return props.getFormulas().getDrought();
    }

    private RpsimProperties.Insurance insuranceCfg() {
        return props.getFormulas().getInsurance();
    }

    // ------------------------------------------------------------------------------------------ W2 growing fields

    /** Every field export: the own fields with a crop in phase GROWING are recorded for the current game month. */
    @EventListener
    @Order(6)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null) {
            return;
        }
        long month = gameTime.monthIndex(sg, e.gameTime());
        for (BridgeDtos.Field field : f.fields()) {
            if (field == null || field.farmlandId() == null || FieldService.phase(field) != FieldPhase.GROWING) {
                continue;
            }
            double ha = field.hectares() == null ? 0 : field.hectares();
            GrowingFieldMonth g = growing.findBySavegameAndMonthIndexAndFarmlandId(sg, month, field.farmlandId())
                    .orElseGet(() -> {
                        GrowingFieldMonth n = new GrowingFieldMonth();
                        n.setSavegame(sg);
                        n.setMonthIndex(month);
                        n.setFarmlandId(field.farmlandId());
                        return n;
                    });
            g.setHectares(Math.max(g.getHectares(), ha));
            g.setFieldName(field.name());
            g.setFruitType(field.fruitType());
            growing.save(g);
        }
    }

    // ------------------------------------------------------------------------------------------ W1 detection

    /** Rating of one game month. */
    public enum MonthRating { DRY, WET, UNKNOWN, OUTSIDE }

    /** Rain share and observed share (0..1) of a month; null when nothing was observed. */
    public record MonthRain(Double rainShare, double observedShare) {
    }

    public MonthRain rain(Savegame sg, long monthIndex) {
        Optional<RainPeriod> rp = fields.rainPeriod(sg, monthIndex);
        if (rp.isEmpty() || rp.get().getObservedMs() <= 0) {
            return new MonthRain(null, 0);
        }
        return new MonthRain(rp.get().rainShare(), Math.min(1, rp.get().getObservedMs() / (double) gameTime.msPerMonth(sg)));
    }

    public MonthRating rate(Savegame sg, long monthIndex) {
        if (!cfg().getPeriods().contains(gameTime.anchor(sg).periodOf(monthIndex))) {
            return MonthRating.OUTSIDE;
        }
        return rate(rain(sg, monthIndex), cfg());
    }

    /** Pure rating of a growth month: dry below max-rain-share, unknown below min-observed-share. */
    public static MonthRating rate(MonthRain r, RpsimProperties.Drought cfg) {
        if (r.rainShare() == null || r.observedShare() < cfg.getMinObservedShare()) {
            return MonthRating.UNKNOWN;
        }
        return r.rainShare() < cfg.getMaxRainShare() ? MonthRating.DRY : MonthRating.WET;
    }

    /** Month start: rate the month that just ended and move the series on. */
    @EventListener
    @Order(75)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        evaluate(sg, e.monthIndex() - 1);
    }

    @Transactional
    public MonthRating evaluate(Savegame sg, long monthIndex) {
        MonthRating rating = rate(sg, monthIndex);
        if (rating != MonthRating.DRY) {
            sg.setDroughtDryMonths(0);
            sg.setDroughtSeriesStartMonth(null);
            sg.setDroughtSeriesDeclared(false);
            return rating;
        }
        if (sg.getDroughtDryMonths() == 0) {
            sg.setDroughtSeriesStartMonth(monthIndex);
            warn(sg, monthIndex);
        }
        sg.setDroughtDryMonths(sg.getDroughtDryMonths() + 1);
        if (sg.getDroughtDryMonths() >= cfg().getMinPeriods() && !sg.isDroughtSeriesDeclared()) {
            sg.setDroughtSeriesDeclared(true);
            declare(sg, sg.getDroughtSeriesStartMonth(), monthIndex);
        }
        return rating;
    }

    /** The cooperative warns once at the first dry month of a series; the insurance agent offers the drought cover. */
    void warn(Savegame sg, long monthIndex) {
        MonthRain r = rain(sg, monthIndex);
        int period = gameTime.anchor(sg).periodOf(monthIndex);
        lookup.mandatory(sg, CharacterRole.COOPERATIVE).ifPresent(coop -> narration
                .request(sg, NarrationEventType.DROUGHT_WARNING).from(coop)
                .facts(NarrationFacts.builder().put("month", CalendarText.month(period))
                        .put("rainPercent", percent(r.rainShare()))
                        .put("dryMonthsForDrought", cfg().getMinPeriods()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
        insurance.maybeOfferDrought(sg);
    }

    // ------------------------------------------------------------------------------------------ declaration

    /** One crop standing in the village with its area (own fields growing or ripe, neighbour fields of H1). */
    public record VillageCrop(String fillType, double hectares) {
    }

    public static List<VillageCrop> villageCrops(FarmFacts f) {
        Map<String, Double> area = new LinkedHashMap<>();
        if (f != null) {
            List<BridgeDtos.Field> all = new ArrayList<>();
            if (f.fields() != null) {
                all.addAll(f.fields());
            }
            if (f.npcFields() != null) {
                all.addAll(f.npcFields());
            }
            for (BridgeDtos.Field field : all) {
                if (field == null || !FieldService.phase(field).standing()) {
                    continue;
                }
                String fillType = field.fillType() != null ? field.fillType() : field.fruitType();
                area.merge(fillType, field.hectares() == null ? 0 : field.hectares(), Double::sum);
            }
        }
        return area.entrySet().stream().map(x -> new VillageCrop(x.getKey(), x.getValue()))
                .sorted(Comparator.comparingDouble(VillageCrop::hectares).reversed().thenComparing(VillageCrop::fillType))
                .toList();
    }

    @Transactional
    public Drought declare(Savegame sg, long firstMonth, long lastMonth) {
        long now = sg.getCurrentGameTime();
        FarmFacts f = facts.latest(sg).orElse(null);
        Drought d = new Drought();
        d.setSavegame(sg);
        d.setFirstMonthIndex(firstMonth);
        d.setLastMonthIndex(lastMonth);
        d.setDeclaredGameTime(now);
        d.setInsuranceResult(Drought.NONE);
        droughts.save(d);

        List<String> crops = harvestFailures(sg, f, d);
        Optional<Character> coop = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        long months = lastMonth - firstMonth + 1;
        coop.ifPresent(c -> narration.request(sg, NarrationEventType.DROUGHT_DECLARED).from(c)
                .facts(NarrationFacts.builder().put("dryMonths", months)
                        .put("crops", crops.isEmpty() ? "die Ernte"
                                : crops.stream().map(labels::label).collect(Collectors.joining(", ")))
                        .put("priceEvents", d.getPriceEvents()).build())
                .category(CommunicationCategory.MARKET).related(RELATED, d.getId()).formLink("/market").submit());
        diary.addAuto(sg, "MARKET", "Dürre ausgerufen", months + " trockene Monate in Folge."
                + (crops.isEmpty() ? "" : " Die Preise für " + crops.stream().map(labels::label)
                .collect(Collectors.joining(", ")) + " steigen in der Region."), RELATED, d.getId());
        gossip(sg, f, firstMonth, lastMonth);
        insurancePayout(sg, d, firstMonth);
        aid(sg, d);
        return d;
    }

    /**
     * HARVEST_FAILURE for at most max-crops crops (largest area first) at every sell point accepting them; pairs with an
     * open market event or a fixed price (special offer, forward contract) are skipped.
     */
    List<String> harvestFailures(Savegame sg, FarmFacts f, Drought d) {
        Optional<BridgeDtos.MarketContext> ctx = facts.marketContext(sg);
        List<String> crops = new ArrayList<>();
        if (ctx.isEmpty()) {
            return crops;
        }
        Set<String> skip = market.busyPairs(sg);
        skip.addAll(forwards.openPairs(sg));
        int events = 0;
        for (VillageCrop crop : villageCrops(f).stream().limit(cfg().getMaxCrops()).toList()) {
            boolean any = false;
            for (BridgeDtos.SellPoint sp : ctx.get().sellPoints()) {
                if (sp.acceptedFillTypes() == null || !sp.acceptedFillTypes().contains(crop.fillType()) || skip.contains(sp.id() + "|" + crop.fillType())) {
                    continue;
                }
                market.spawnPriceEventAt(sg, MarketEventType.HARVEST_FAILURE,
                        new MarketEventEngine.Target(sp.id(), sp.name(), crop.fillType()), false, null);
                events++;
                any = true;
            }
            if (any) {
                crops.add(crop.fillType());
            }
        }
        d.setCrops(crops.isEmpty() ? null : String.join(",", crops));
        d.setPriceEvents(events);
        return crops;
    }

    /** One FIELD_GOSSIP (topic DROUGHT) by a random villager or neighbour about the largest own field with a crop. */
    void gossip(Savegame sg, FarmFacts f, long firstMonth, long lastMonth) {
        Optional<BridgeDtos.Field> field = f == null || f.fields() == null ? Optional.empty() : f.fields().stream()
                .filter(x -> x != null && x.farmlandId() != null && FieldService.phase(x).standing())
                .max(Comparator.comparingDouble(x -> x.hectares() == null ? 0 : x.hectares()));
        String name;
        String fruit;
        if (field.isPresent()) {
            name = field.get().name() == null ? String.valueOf(field.get().farmlandId()) : field.get().name();
            fruit = field.get().fruitType();
        } else {
            Optional<GrowingFieldMonth> g = growing.findBySavegameAndMonthIndexBetweenOrderByFarmlandIdAsc(sg, firstMonth,
                    lastMonth).stream().max(Comparator.comparingDouble(GrowingFieldMonth::getHectares));
            if (g.isEmpty()) {
                return; // no own field with a crop - nothing to talk about
            }
            name = g.get().getFieldName() == null ? String.valueOf(g.get().getFarmlandId()) : g.get().getFieldName();
            fruit = g.get().getFruitType();
        }
        List<Character> dyn = lookup.activeDynamic(sg);
        Optional<Character> teller = dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER, CharacterRole.NEIGHBOR_FARMER)
                : Optional.of(random.pick(dyn));
        teller.ifPresent(t -> narration.request(sg, NarrationEventType.FIELD_GOSSIP).from(t)
                .facts(NarrationFacts.builder().put("fieldName", name).put("topic", "DROUGHT").put("fruitType", fruit)
                        .build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
    }

    /**
     * W3: the running drought insurance pays payout-per-hectare × the insured area at the declaration (INSURANCE_PAYOUT,
     * no claim) - only when paid up and concluded before the first dry month of the series.
     */
    void insurancePayout(Savegame sg, Drought d, long firstMonth) {
        Optional<Contract> c = insurance.activeDrought(sg);
        if (c.isEmpty()) {
            return;
        }
        d.setInsuranceContractId(c.get().getId());
        if (c.get().getStartedAtGameTime() == null || c.get().getStartedAtGameTime() >= gameTime.monthStart(sg, firstMonth)) {
            d.setInsuranceResult(Drought.TOO_LATE);
            return;
        }
        if (c.get().isPaymentOverdue()) {
            d.setInsuranceResult(Drought.COVER_SUSPENDED);
            return;
        }
        double ha = insurance.droughtArea(sg);
        long payout = insurance.droughtPayout(ha);
        d.setInsuredHectares(Math.round(ha * 100) / 100.0);
        d.setInsurancePayout(payout);
        d.setInsuranceResult(Drought.PAID);
        if (payout <= 0) {
            return;
        }
        outbox.money(sg, payout, MoneyReason.INSURANCE_PAYOUT, "Dürreversicherung", new Related(RELATED, d.getId()));
        narration.request(sg, NarrationEventType.DROUGHT_INSURANCE_PAYOUT).from(c.get().getCharacter())
                .facts(NarrationFacts.builder().put("hectares", Math.round(ha * 10) / 10.0)
                        .put("payoutPerHectare", insuranceCfg().getDroughtPayoutPerHectare()).put("payout", payout).build())
                .category(CommunicationCategory.INSURANCE).related(ContractBillingService.RELATED, c.get().getId()).submit();
        diary.addAuto(sg, "INSURANCE", "Dürreversicherung zahlt", "Auszahlung " + payout + " € für "
                + Math.round(ha * 10) / 10.0 + " ha ohne Schadensmeldung.", RELATED, d.getId());
    }

    // ------------------------------------------------------------------------------------------ W2 drought aid

    /** Hectares of own fields that were growing in one of the drought months (each field once, its largest area). */
    public double aidHectares(Savegame sg, long firstMonth, long lastMonth) {
        Map<Integer, Double> byField = new HashMap<>();
        for (GrowingFieldMonth g : growing.findBySavegameAndMonthIndexBetweenOrderByFarmlandIdAsc(sg, firstMonth, lastMonth)) {
            byField.merge(g.getFarmlandId(), g.getHectares(), Math::max);
        }
        return byField.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Aid = hectares × aid-per-hectare, minus aid-insurance-deduction with a running drought insurance. */
    public static long aidAmount(double hectares, boolean insured, RpsimProperties.Drought cfg) {
        return Math.round(hectares * cfg.getAidPerHectare() * (insured ? 1 - cfg.getAidInsuranceDeduction() : 1));
    }

    void aid(Savegame sg, Drought d) {
        double ha = aidHectares(sg, d.getFirstMonthIndex(), d.getLastMonthIndex());
        d.setAidHectares(Math.round(ha * 100) / 100.0);
        Optional<Character> authority = lookup.mandatory(sg, CharacterRole.AUTHORITY);
        if (ha <= 0 || authority.isEmpty()) {
            return;
        }
        boolean insured = insurance.activeDrought(sg).isPresent();
        long amount = aidAmount(ha, insured, cfg());
        if (amount <= 0) {
            return;
        }
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.DROUGHT_AID);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(authority.get());
        sc.setHectares(d.getAidHectares());
        sc.setOfferAmount(amount);
        sc.setCostAmount(aidAmount(ha, false, cfg()) - amount); // deduction for a running drought insurance
        sc.setReference(String.valueOf(d.getId()));
        sc.setTitle("Dürrehilfe");
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getAidApplicationDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        d.setAidCaseId(sc.getId());
        narration.request(sg, NarrationEventType.DROUGHT_AID_OFFER).from(authority.get())
                .facts(NarrationFacts.builder().put("hectares", Math.round(ha * 10) / 10.0)
                        .put("aidPerHectare", cfg().getAidPerHectare()).put("amount", amount).put("insured", insured)
                        .put("deductionPercent", insured ? Math.round(cfg().getAidInsuranceDeduction() * 100) : 0)
                        .put("deductionNote", insured ? "Weil Sie eine Dürreversicherung haben, ist die Hilfe um "
                                + Math.round(cfg().getAidInsuranceDeduction() * 100) + " % gekürzt. " : "")
                        .put("applicationDays", Math.round(cfg().getAidApplicationDays())).build())
                .category(CommunicationCategory.GENERAL).related(CASE_RELATED, sc.getId())
                .formLink("/aemter?case=" + sc.getId()).submit();
    }

    /** "Antrag stellen": the aid is paid at once as SUBSIDY. */
    @Transactional
    public ServiceCase apply(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.DROUGHT_AID || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Antrag ist bereits erledigt.");
        }
        long now = sg.getCurrentGameTime();
        if (sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
            throw new BusinessRuleException("DEADLINE_MISSED", "Die Antragsfrist ist abgelaufen.");
        }
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("PAID");
        sc.setPayoutAmount(sc.getOfferAmount());
        sc.setClosedAtGameTime(now);
        outbox.money(sg, sc.getOfferAmount(), MoneyReason.SUBSIDY, "Dürrehilfe", new Related(CASE_RELATED, sc.getId()));
        diary.addAuto(sg, "MARKET", "Dürrehilfe erhalten", sc.getOfferAmount() + " € für " + sc.getHectares()
                + " ha vom Amt.", CASE_RELATED, sc.getId());
        return sc;
    }

    /** Daily: an aid not applied for within the deadline lapses. */
    @EventListener
    @Order(79)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.DROUGHT_AID && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setResolution("DEADLINE_MISSED");
                sc.setClosedAtGameTime(now);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ W3 premium

    /**
     * Month start, before the billing (order 20): the premium of the drought insurance follows the current insured area
     * (unchanged while the area is unknown or 0).
     */
    @EventListener
    @Order(15)
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Optional<Contract> c = insurance.activeDrought(sg);
        if (c.isEmpty() || c.get().getNextDueGameTime() == null || c.get().getNextDueGameTime() > sg.getCurrentGameTime()) {
            return;
        }
        double ha = insurance.droughtArea(sg);
        if (ha > 0) {
            c.get().setMonthlyAmount(insurance.droughtPremium(ha));
        }
    }

    // ------------------------------------------------------------------------------------------ status

    public List<Drought> list(Savegame sg) {
        return droughts.findBySavegameOrderByIdDesc(sg);
    }

    public List<ServiceCase> aidCases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.DROUGHT_AID));
    }

    static Double percent(Double share) {
        return share == null ? null : Math.round(share * 1000) / 10.0;
    }
}
