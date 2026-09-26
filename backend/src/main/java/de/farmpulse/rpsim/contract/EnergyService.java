package de.farmpulse.rpsim.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeDtos.SellPoint;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
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
 * TODO T-20 energy supplier: buys biomass / biogas at the sell points of the map that accept one of the configured
 * fill types ({@code rpsim.formulas.energy.fill-types}). Offers are the existing market mechanics - fixed-price
 * contracts (PRICE_EVENT FIXED, player decides) and price fluctuations (PRICE_EVENT MULTIPLIER) - restricted to those
 * pairs and announced by the energy supplier. Without such a sell point in {@code market_context.json} the supplier
 * does not appear at all: the tool never invents a biogas plant.
 */
@Service
public class EnergyService {

    private static final Set<MarketEventStatus> OPEN = Set.of(MarketEventStatus.PLANNED, MarketEventStatus.OFFERED,
            MarketEventStatus.ACTIVE);

    private final SavegameRepository savegames;
    private final MarketEventRepository events;
    private final FactsService facts;
    private final MarketEventEngine market;
    private final ServiceRoleService roles;
    private final RandomSource random;
    private final RpsimProperties props;

    public EnergyService(SavegameRepository savegames, MarketEventRepository events, FactsService facts,
                         MarketEventEngine market, ServiceRoleService roles, RandomSource random, RpsimProperties props) {
        this.savegames = savegames;
        this.events = events;
        this.facts = facts;
        this.market = market;
        this.roles = roles;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.Energy cfg() {
        return props.getFormulas().getEnergy();
    }

    /** "sellPoint|fillType" pairs the energy supplier may use (configured fill types at accepting sell points). */
    public static Set<String> energyPairs(MarketContext ctx, List<String> fillTypes) {
        Set<String> out = new HashSet<>();
        if (ctx == null) {
            return out;
        }
        for (SellPoint sp : ctx.sellPoints()) {
            for (String ft : sp.acceptedFillTypes()) {
                if (fillTypes.contains(ft)) {
                    out.add(sp.id() + "|" + ft);
                }
            }
        }
        return out;
    }

    /** All other pairs of the map - excluded from the target selection. */
    static Set<String> otherPairs(MarketContext ctx, Set<String> allowed) {
        Set<String> out = new HashSet<>();
        for (SellPoint sp : ctx.sellPoints()) {
            for (String ft : sp.acceptedFillTypes()) {
                String pair = sp.id() + "|" + ft;
                if (!allowed.contains(pair)) {
                    out.add(pair);
                }
            }
        }
        return out;
    }

    long openCount(Savegame sg) {
        return events.findBySavegameAndStatusIn(sg, OPEN).stream()
                .filter(ev -> ev.getCharacter() != null && ev.getCharacter().getRole() == CharacterRole.ENERGY_SUPPLIER)
                .count();
    }

    @EventListener
    @Order(73)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (random.chance(cfg().getProbabilityPerMonth())) {
            offer(sg);
        }
    }

    /** One offer of the energy supplier, if the map has a suitable sell point and the cap allows it. */
    @Transactional
    public Optional<MarketEvent> offer(Savegame sg) {
        Optional<MarketContext> ctx = facts.marketContext(sg);
        Set<String> allowed = energyPairs(ctx.orElse(null), cfg().getFillTypes());
        allowed.removeAll(market.busyPairs(sg));
        if (allowed.isEmpty() || openCount(sg) >= cfg().getMaxOpen()) {
            return Optional.empty();
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        Set<String> excluded = otherPairs(ctx.get(), allowed);
        boolean contract = random.chance(cfg().getContractShare());
        if (contract && !hasPrice(f, allowed)) {
            contract = false; // a fixed price needs the current price of the pair
        }
        Character supplier = roles.ensure(sg, CharacterRole.ENERGY_SUPPLIER);
        if (contract) {
            return market.spawnSpecialOffer(sg, ctx.get(), f, excluded, supplier);
        }
        MarketEventType type = random.chance(cfg().getSpikeShare()) ? MarketEventType.DEMAND_SPIKE
                : MarketEventType.DEMAND_SLUMP;
        return market.spawnPriceEvent(sg, type, ctx.get(), f, sg.getCurrentGameTime(), true, excluded, supplier);
    }

    private static boolean hasPrice(FarmFacts f, Set<String> pairs) {
        return f != null && f.prices().stream()
                .anyMatch(p -> p.currentPrice() != null && p.currentPrice() > 0 && pairs.contains(p.sellPoint() + "|" + p.fillType()));
    }
}
