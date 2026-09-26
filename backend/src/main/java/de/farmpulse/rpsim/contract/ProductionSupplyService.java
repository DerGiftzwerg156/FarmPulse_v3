package de.farmpulse.rpsim.contract;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeDtos.SellPoint;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-22 delivery contracts with production points of the map. A production (bakery, dairy ...) that buys goods
 * has a selling station in the game (PlaceableProductionPoint: productionPoint.unloadingStation); the mod marks these
 * sell points as {@code production} in market_context.json. The contract is the existing fixed-price contract
 * (PRICE_EVENT FIXED, the player decides on the market page) restricted to such sell points; the player's own
 * productions are left out.
 */
@Service
public class ProductionSupplyService {

    private static final Set<MarketEventStatus> OPEN = Set.of(MarketEventStatus.OFFERED, MarketEventStatus.ACTIVE);

    private final SavegameRepository savegames;
    private final MarketEventRepository events;
    private final FactsService facts;
    private final MarketEventEngine market;
    private final CharacterLookup lookup;
    private final RandomSource random;
    private final RpsimProperties props;

    public ProductionSupplyService(SavegameRepository savegames, MarketEventRepository events, FactsService facts,
                                   MarketEventEngine market, CharacterLookup lookup, RandomSource random,
                                   RpsimProperties props) {
        this.savegames = savegames;
        this.events = events;
        this.facts = facts;
        this.market = market;
        this.lookup = lookup;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.ProductionSupply cfg() {
        return props.getFormulas().getProductionSupply();
    }

    /** Ids of the sell points that are production points of the map (not the player's own). */
    public static Set<String> productionSellPoints(MarketContext ctx) {
        Set<String> ids = new HashSet<>();
        if (ctx != null) {
            ctx.sellPoints().stream().filter(SellPoint::foreignProduction).forEach(sp -> ids.add(sp.id()));
        }
        return ids;
    }

    long openCount(Savegame sg, Set<String> productions) {
        return events.findBySavegameAndStatusIn(sg, OPEN).stream()
                .filter(ev -> ev.getEventType() == MarketEventType.SPECIAL_OFFER && productions.contains(ev.getSellPoint()))
                .count();
    }

    @EventListener
    @Order(76)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (random.chance(cfg().getProbabilityPerMonth())) {
            offer(sg);
        }
    }

    /** One delivery contract offer at a production point, if the map has one and the cap allows it. */
    @Transactional
    public Optional<MarketEvent> offer(Savegame sg) {
        Optional<MarketContext> ctx = facts.marketContext(sg);
        Set<String> productions = productionSellPoints(ctx.orElse(null));
        if (productions.isEmpty() || openCount(sg, productions) >= cfg().getMaxOpen()) {
            return Optional.empty();
        }
        Set<String> excluded = new HashSet<>();
        for (SellPoint sp : ctx.get().sellPoints()) {
            if (!productions.contains(sp.id())) {
                sp.acceptedFillTypes().forEach(ft -> excluded.add(sp.id() + "|" + ft));
            }
        }
        return market.spawnSpecialOffer(sg, ctx.get(), facts.latest(sg).orElse(null), excluded,
                lookup.firstActive(sg, CharacterRole.COOPERATIVE, CharacterRole.SUPPLIER, CharacterRole.LAND_AGENT).orElse(null));
    }
}
