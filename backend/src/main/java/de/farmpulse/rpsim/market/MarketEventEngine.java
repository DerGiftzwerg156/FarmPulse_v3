package de.farmpulse.rpsim.market;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic event engine (functional concept "Event-/Preissystem", technical concept "Preis-Events &
 * Sonderkontrakte"): decides type, product, sell point, strength and duration within fixed bands. The AI
 * only receives the finished package and narrates it through a character.
 */
@Service
public class MarketEventEngine {

    public static final String RELATED = "MARKET_EVENT";
    static final Set<MarketEventType> PRICE_TYPES = EnumSet.of(MarketEventType.DEMAND_SPIKE, MarketEventType.DEMAND_SLUMP,
            MarketEventType.HARVEST_FAILURE, MarketEventType.HARVEST_SURPLUS);
    static final Set<MarketEventStatus> OPEN = EnumSet.of(MarketEventStatus.PLANNED, MarketEventStatus.OFFERED,
            MarketEventStatus.ACTIVE);

    private final MarketEventRepository events;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final CharacterLookup lookup;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;

    public MarketEventEngine(MarketEventRepository events, SavegameRepository savegames, FactsService facts,
                             OutboxService outbox, NarrationRequestService narration, CharacterLookup lookup,
                             DiaryService diary, RandomSource random, RpsimProperties props) {
        this.events = events;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.narration = narration;
        this.lookup = lookup;
        this.diary = diary;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.Market cfg() {
        return props.getFormulas().getMarket();
    }

    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        updateLifecycle(sg);
        if (random.chance(cfg().getDailySpawnProbability())) {
            spawn(sg);
        }
    }

    /** PLANNED -> ACTIVE at start, ACTIVE/OFFERED -> ENDED after end/deadline. */
    @Transactional
    public void updateLifecycle(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (MarketEvent ev : events.findBySavegameAndStatusIn(sg, OPEN)) {
            if (ev.getStatus() == MarketEventStatus.PLANNED && ev.getStartGameTime() <= now) {
                ev.setStatus(MarketEventStatus.ACTIVE);
                if (!ev.isAnnounced()) {
                    announce(sg, ev);
                }
            }
            if (ev.getStatus() == MarketEventStatus.OFFERED && ev.getDeadlineGameTime() != null
                    && ev.getDeadlineGameTime() <= now) {
                ev.setStatus(MarketEventStatus.ENDED);
                ev.setEndReason("NOT_ACCEPTED");
            }
            if (ev.getStatus() == MarketEventStatus.ACTIVE && ev.getEndGameTime() != null && ev.getEndGameTime() <= now
                    && ev.getEventType() != MarketEventType.SPECIAL_OFFER) {
                ev.setStatus(MarketEventStatus.ENDED);
            }
        }
    }

    long activeCount(Savegame sg) {
        return events.findBySavegameAndStatusIn(sg, OPEN).stream()
                .filter(ev -> ev.getEventType() != MarketEventType.RUMOR).count();
    }

    /** Spawns one event unless the concurrency cap is reached. Returns the created event (if any). */
    @Transactional
    public Optional<MarketEvent> spawn(Savegame sg) {
        if (activeCount(sg) >= cfg().getMaxActiveEvents()) {
            return Optional.empty();
        }
        Optional<MarketContext> ctx = facts.marketContext(sg);
        if (ctx.isEmpty() || ctx.get().sellPoints().isEmpty()) {
            return Optional.empty();
        }
        Map<MarketEventType, Double> weights = new LinkedHashMap<>();
        cfg().getTypeWeights().forEach((k, v) -> weights.put(MarketEventType.valueOf(k), v));
        MarketEventType type = random.weighted(weights);
        FarmFacts f = facts.latest(sg).orElse(null);
        return switch (type) {
            case SUBSIDY -> Optional.of(spawnSubsidy(sg));
            case RUMOR -> spawnRumor(sg, ctx.get(), f);
            case SPECIAL_OFFER -> spawnSpecialOffer(sg, ctx.get(), f);
            default -> spawnPriceEvent(sg, type, ctx.get(), f, advanceNoticeStart(sg), true);
        };
    }

    private long advanceNoticeStart(Savegame sg) {
        long now = sg.getCurrentGameTime();
        if (random.chance(cfg().getAdvanceNoticeProbability())) {
            return now + GameTime.days(random.intBetween(cfg().getAdvanceNoticeDaysMin(), cfg().getAdvanceNoticeDaysMax()));
        }
        return now;
    }

    /** Sell point / fill type pair. */
    public record Target(String sellPoint, String sellPointName, String fillType) {
    }

    /**
     * Target selection weighted by the stored silo stock: weight(fillType) = base + stockWeight * share of that fill
     * type in the total classic-silo stock. A fill type without stock is chosen less often (technical concept
     * "Warenbestand-Bewertung"/"Zielauswahl"). The sell point is then picked among those accepting it (regional).
     */
    public Optional<Target> pickTarget(MarketContext ctx, FarmFacts f, Set<String> excludedPairs) {
        Map<String, Double> stock = new LinkedHashMap<>();
        double total = 0;
        if (f != null) {
            for (BridgeDtos.StorageEntry s : f.assets().storage()) {
                stock.merge(s.fillType(), s.amount(), Double::sum);
                total += s.amount();
            }
        }
        Map<String, List<BridgeDtos.SellPoint>> byFillType = new LinkedHashMap<>();
        for (BridgeDtos.SellPoint sp : ctx.sellPoints()) {
            for (String ft : sp.acceptedFillTypes()) {
                if (!excludedPairs.contains(sp.id() + "|" + ft)) {
                    byFillType.computeIfAbsent(ft, k -> new ArrayList<>()).add(sp);
                }
            }
        }
        if (byFillType.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String ft : byFillType.keySet()) {
            double share = total > 0 ? stock.getOrDefault(ft, 0.0) / total : 0;
            weights.put(ft, cfg().getTargetBaseWeight() + cfg().getTargetStockWeight() * share);
        }
        String fillType = random.weighted(weights);
        BridgeDtos.SellPoint sp = random.pick(byFillType.get(fillType));
        return Optional.of(new Target(sp.id(), sp.name(), fillType));
    }

    private Set<String> busyPairs(Savegame sg) {
        Set<String> s = new java.util.HashSet<>();
        for (MarketEvent ev : events.findBySavegameAndStatusIn(sg, OPEN)) {
            if (ev.getSellPoint() != null && ev.getEventType() != MarketEventType.RUMOR) {
                s.add(ev.getSellPoint() + "|" + ev.getFillType());
            }
        }
        return s;
    }

    public Optional<MarketEvent> spawnPriceEvent(Savegame sg, MarketEventType type, MarketContext ctx, FarmFacts f, long start,
                                          boolean announceNow) {
        Optional<Target> t = pickTarget(ctx, f, busyPairs(sg));
        if (t.isEmpty()) {
            return Optional.empty();
        }
        RpsimProperties.Band band = cfg().getBands().get(type.name());
        MarketEvent ev = base(sg, type, start);
        ev.setSellPoint(t.get().sellPoint());
        ev.setFillType(t.get().fillType());
        ev.setPeakMultiplier(round(random.uniform(band.getMultiplierMin(), band.getMultiplierMax()), 3));
        ev.setRampUpHours((double) Math.round(random.uniform(band.getRampHoursMin(), band.getRampHoursMax())));
        ev.setHoldHours((double) Math.round(random.uniform(band.getHoldHoursMin(), band.getHoldHoursMax())));
        ev.setDecayHours((double) Math.round(random.uniform(band.getDecayHoursMin(), band.getDecayHoursMax())));
        ev.setEndGameTime(start + GameTime.hours(ev.getRampUpHours() + ev.getHoldHours() + ev.getDecayHours()));
        ev.setStatus(start > sg.getCurrentGameTime() ? MarketEventStatus.PLANNED : MarketEventStatus.ACTIVE);
        ev.setCharacter(characterFor(sg, type).orElse(null));
        events.save(ev);
        var ins = outbox.priceMultiplier(sg, ev.getFillType(), ev.getSellPoint(), ev.getPeakMultiplier(), ev.getRampUpHours(),
                ev.getHoldHours(), ev.getDecayHours(), start, new Related(RELATED, ev.getId()));
        ev.setInstructionId(ins.getInstructionId());
        if (announceNow) {
            announce(sg, ev);
        }
        return Optional.of(ev);
    }

    public Optional<MarketEvent> spawnSpecialOffer(Savegame sg, MarketContext ctx, FarmFacts f) {
        if (f == null) {
            return Optional.empty();
        }
        // only pairs with a known current price can get a fixed-price contract
        Set<String> excluded = busyPairs(sg);
        Set<String> priced = new java.util.HashSet<>();
        f.prices().forEach(p -> priced.add(p.sellPoint() + "|" + p.fillType()));
        ctx.sellPoints().forEach(sp -> sp.acceptedFillTypes().forEach(ft -> {
            if (!priced.contains(sp.id() + "|" + ft)) {
                excluded.add(sp.id() + "|" + ft);
            }
        }));
        Optional<Target> t = pickTarget(ctx, f, excluded);
        if (t.isEmpty()) {
            return Optional.empty();
        }
        Optional<Double> current = f.prices().stream()
                .filter(p -> p.sellPoint().equals(t.get().sellPoint()) && p.fillType().equals(t.get().fillType()))
                .map(BridgeDtos.Price::currentPrice).findFirst();
        if (current.isEmpty() || current.get() <= 0) {
            return Optional.empty();
        }
        long now = sg.getCurrentGameTime();
        MarketEvent ev = base(sg, MarketEventType.SPECIAL_OFFER, now);
        ev.setSellPoint(t.get().sellPoint());
        ev.setFillType(t.get().fillType());
        ev.setFixedPrice(Math.round(current.get() * random.uniform(cfg().getSpecialOfferPremiumMin(),
                cfg().getSpecialOfferPremiumMax())));
        ev.setMaxQuantity(Math.round(random.uniform(cfg().getSpecialOfferQuantityMin(),
                cfg().getSpecialOfferQuantityMax()) / 1000.0) * 1000);
        ev.setDeadlineGameTime(now + GameTime.days(random.uniform(cfg().getSpecialOfferDaysMin(),
                cfg().getSpecialOfferDaysMax())));
        ev.setEndGameTime(ev.getDeadlineGameTime());
        // Player reaction: "Aushandeln" = participation decision, no free-text price negotiation.
        ev.setStatus(MarketEventStatus.OFFERED);
        ev.setCharacter(characterFor(sg, MarketEventType.SPECIAL_OFFER).orElse(null));
        events.save(ev);
        announce(sg, ev);
        return Optional.of(ev);
    }

    public MarketEvent spawnSubsidy(Savegame sg) {
        long start = advanceNoticeStart(sg);
        MarketEvent ev = base(sg, MarketEventType.SUBSIDY, start);
        ev.setSubsidyAmount(Math.round(random.uniform(cfg().getSubsidyAmountMin(), cfg().getSubsidyAmountMax()) / 100.0) * 100);
        ev.setEndGameTime(start);
        ev.setStatus(start > sg.getCurrentGameTime() ? MarketEventStatus.PLANNED : MarketEventStatus.ENDED);
        ev.setCharacter(characterFor(sg, MarketEventType.SUBSIDY).orElse(null));
        events.save(ev);
        // SUBSIDY is a money event, not a price event.
        var ins = outbox.money(sg, ev.getSubsidyAmount(), MoneyReason.SUBSIDY, "Förderung", new Related(RELATED, ev.getId()),
                null, start > sg.getCurrentGameTime() ? start : null);
        ev.setInstructionId(ins.getInstructionId());
        announce(sg, ev);
        return ev;
    }

    /**
     * Rumour mechanic: ~70 % reference a real, already planned event (distorted description), ~30 % are made up.
     * Controlled through the isAccurate flag that is passed to the prompt context.
     */
    public Optional<MarketEvent> spawnRumor(Savegame sg, MarketContext ctx, FarmFacts f) {
        long now = sg.getCurrentGameTime();
        MarketEvent rumor = base(sg, MarketEventType.RUMOR, now);
        rumor.setStatus(MarketEventStatus.RUMOR_ONLY);
        rumor.setCharacter(characterFor(sg, MarketEventType.RUMOR).orElse(null));
        if (random.chance(cfg().getRumorAccurateProbability())) {
            Optional<MarketEvent> planned = events.findBySavegameAndStatusIn(sg, EnumSet.of(MarketEventStatus.PLANNED))
                    .stream().filter(ev -> PRICE_TYPES.contains(ev.getEventType())).findFirst();
            if (planned.isEmpty()) {
                MarketEventType type = random.pick(List.copyOf(PRICE_TYPES));
                long start = now + GameTime.days(random.intBetween(cfg().getAdvanceNoticeDaysMin(),
                        cfg().getAdvanceNoticeDaysMax()));
                planned = spawnPriceEvent(sg, type, ctx, f, start, false);
            }
            if (planned.isEmpty()) {
                return Optional.empty();
            }
            MarketEvent real = planned.get();
            rumor.setIsAccurate(true);
            rumor.setReferencedEventId(real.getId());
            rumor.setFillType(real.getFillType());
            rumor.setSellPoint(real.getSellPoint());
            rumor.setPeakMultiplier(real.getPeakMultiplier());
            rumor.setStartGameTime(real.getStartGameTime());
        } else {
            Optional<Target> t = pickTarget(ctx, f, Set.of());
            if (t.isEmpty()) {
                return Optional.empty();
            }
            rumor.setIsAccurate(false);
            rumor.setFillType(t.get().fillType());
            rumor.setSellPoint(t.get().sellPoint());
            boolean up = random.chance(0.5);
            rumor.setPeakMultiplier(round(up ? random.uniform(1.08, 1.25) : random.uniform(0.75, 0.92), 3));
        }
        events.save(rumor);
        announce(sg, rumor);
        return Optional.of(rumor);
    }

    private MarketEvent base(Savegame sg, MarketEventType type, long start) {
        MarketEvent ev = new MarketEvent();
        ev.setSavegame(sg);
        ev.setEventType(type);
        ev.setStartGameTime(start);
        ev.setAnnouncedAtGameTime(sg.getCurrentGameTime());
        return ev;
    }

    /** Role mapping: land agent/cooperative for market events, neighbour for harvest/rumours, authority for subsidies. */
    Optional<Character> characterFor(Savegame sg, MarketEventType type) {
        return switch (type) {
            case DEMAND_SPIKE, DEMAND_SLUMP, SPECIAL_OFFER -> lookup.firstActive(sg, CharacterRole.LAND_AGENT,
                    CharacterRole.COOPERATIVE, CharacterRole.SUPPLIER);
            case HARVEST_FAILURE, HARVEST_SURPLUS, RUMOR -> lookup.firstActive(sg, CharacterRole.NEIGHBOR_FARMER,
                    CharacterRole.VILLAGER, CharacterRole.LAND_AGENT);
            case SUBSIDY -> lookup.firstActive(sg, CharacterRole.AUTHORITY, CharacterRole.COOPERATIVE);
        };
    }

    private void announce(Savegame sg, MarketEvent ev) {
        ev.setAnnounced(true);
        NarrationFacts.Builder b = NarrationFacts.builder().put("eventType", ev.getEventType())
                .put("fillType", ev.getFillType()).put("sellPoint", sellPointName(sg, ev.getSellPoint()));
        long now = sg.getCurrentGameTime();
        double startsInDays = Math.max(0, Math.round(GameTime.toDays(ev.getStartGameTime() - now) * 10) / 10.0);
        NarrationEventType type;
        switch (ev.getEventType()) {
            case SUBSIDY -> {
                type = NarrationEventType.MARKET_SUBSIDY;
                b.put("amount", ev.getSubsidyAmount()).put("startsInDays", startsInDays);
            }
            case SPECIAL_OFFER -> {
                type = NarrationEventType.MARKET_SPECIAL_OFFER;
                b.put("fixedPrice", ev.getFixedPrice()).put("maxQuantity", ev.getMaxQuantity())
                        .put("deadlineDays", Math.round(GameTime.toDays(ev.getDeadlineGameTime() - now) * 10) / 10.0);
            }
            case RUMOR -> {
                type = NarrationEventType.MARKET_RUMOR;
                b.put("isAccurate", ev.getIsAccurate()).put("direction", ev.getPeakMultiplier() >= 1 ? "UP" : "DOWN");
            }
            default -> {
                type = NarrationEventType.MARKET_PRICE_EVENT;
                b.put("changePercent", Math.round((ev.getPeakMultiplier() - 1) * 100))
                        .put("durationDays", Math.round(GameTime.toDays(ev.getEndGameTime() - ev.getStartGameTime())))
                        .put("startsInDays", startsInDays);
            }
        }
        Channel channel = random.chance(cfg().getEventCallProbability()) ? Channel.CALL : Channel.MAIL;
        narration.request(sg, type).from(ev.getCharacter()).facts(b.build()).channel(channel)
                .category(CommunicationCategory.MARKET).related(RELATED, ev.getId())
                .formLink(ev.getEventType() == MarketEventType.SPECIAL_OFFER ? "/market?event=" + ev.getId() : null)
                .submit();
    }

    private String sellPointName(Savegame sg, String id) {
        if (id == null) {
            return null;
        }
        return facts.marketContext(sg).flatMap(c -> c.sellPoints().stream().filter(s -> s.id().equals(id)).findFirst())
                .map(BridgeDtos.SellPoint::name).orElse(id);
    }

    /** Player reaction to a special contract: participate or decline (no free-text price negotiation). */
    @Transactional
    public MarketEvent decideParticipation(Savegame sg, Long eventId, boolean participate) {
        MarketEvent ev = events.findById(eventId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("market event " + eventId));
        if (ev.getStatus() != MarketEventStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        ev.setPlayerParticipation(participate);
        if (!participate) {
            ev.setStatus(MarketEventStatus.DECLINED);
            return ev;
        }
        ev.setStatus(MarketEventStatus.ACTIVE);
        var ins = outbox.priceFixed(sg, ev.getFillType(), ev.getSellPoint(), ev.getFixedPrice(), ev.getMaxQuantity(),
                ev.getDeadlineGameTime(), null, new Related(RELATED, ev.getId()));
        ev.setInstructionId(ins.getInstructionId());
        diary.addAuto(sg, "MARKET", "Sonderkontrakt angenommen", "Kontrakt über " + ev.getMaxQuantity() + " l "
                + ev.getFillType() + " zu " + ev.getFixedPrice() + " €/1000 l.", RELATED, ev.getId());
        return ev;
    }

    /** Reverse channel of FIXED contracts (delivered quantity reported by the mod). */
    @EventListener
    @Transactional
    public void onContractReported(BridgeEvents.ContractReported r) {
        events.findByInstructionId(r.instructionId()).ifPresent(ev -> {
            if (ev.getStatus() == MarketEventStatus.ENDED && ev.getDeliveredQuantity() != null) {
                return;
            }
            Savegame sg = ev.getSavegame();
            ev.setStatus(MarketEventStatus.ENDED);
            ev.setDeliveredQuantity(r.deliveredQuantity());
            ev.setEndReason(r.endReason());
            narration.request(sg, NarrationEventType.MARKET_CONTRACT_ENDED).from(ev.getCharacter())
                    .facts(NarrationFacts.builder().put("fillType", ev.getFillType())
                            .put("deliveredQuantity", r.deliveredQuantity()).put("maxQuantity", r.maxQuantity())
                            .put("endReason", r.endReason()).build())
                    .category(CommunicationCategory.MARKET).related(RELATED, ev.getId()).submit();
            diary.addAuto(sg, "MARKET", "Sonderkontrakt beendet", r.deliveredQuantity() + " von " + r.maxQuantity()
                    + " l geliefert.", RELATED, ev.getId());
        });
    }

    public List<MarketEvent> list(Savegame sg) {
        return events.findBySavegameOrderByIdDesc(sg);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
