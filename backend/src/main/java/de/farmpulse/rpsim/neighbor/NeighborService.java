package de.farmpulse.rpsim.neighbor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.NeighborStock;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NeighborStockRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-H2: the neighbours as trade partners - their role (what they need), their stock (backend fiction fed by
 * their real fields, R3-H1) and the prices. Neighbours are the active NEIGHBOR_FARMER characters; a farmland belongs to
 * one of them through the existing farmland -> character mapping ({@link FarmlandOwnershipService}, T-21).
 */
@Service
public class NeighborService {

    private final CharacterRepository characters;
    private final NeighborStockRepository stocks;
    private final SavegameRepository savegames;
    private final FarmlandOwnershipService ownership;
    private final FactsService facts;
    private final TrustScoreService trust;
    private final RandomSource random;
    private final RpsimProperties props;

    public NeighborService(CharacterRepository characters, NeighborStockRepository stocks, SavegameRepository savegames,
                           FarmlandOwnershipService ownership, FactsService facts, TrustScoreService trust,
                           RandomSource random, RpsimProperties props) {
        this.characters = characters;
        this.stocks = stocks;
        this.savegames = savegames;
        this.ownership = ownership;
        this.facts = facts;
        this.trust = trust;
        this.random = random;
        this.props = props;
    }

    RpsimProperties.NeighborTrade cfg() {
        return props.getFormulas().getNeighborTrade();
    }

    // ------------------------------------------------------------------------------------------ neighbours and roles

    /** Active neighbours; a neighbour created before Roadmap V3 gets his role now. */
    @Transactional
    public List<Character> neighbors(Savegame sg) {
        List<Character> list = characters.findBySavegameAndRoleAndStatus(sg, CharacterRole.NEIGHBOR_FARMER,
                CharacterStatus.ACTIVE);
        list.forEach(this::ensureRole);
        return list;
    }

    /** Owner decision: the role is rolled when the neighbour is created, or when it is first needed. */
    @Transactional
    public String ensureRole(Character c) {
        if (c.getNeighborRole() == null || !cfg().getRoles().containsKey(c.getNeighborRole())) {
            c.setNeighborRole(random.pick(new ArrayList<>(cfg().getRoles().keySet())));
        }
        return c.getNeighborRole();
    }

    /** Fill types the neighbour needs (from his role). */
    public List<String> needs(Character c) {
        return cfg().getRoles().getOrDefault(ensureRole(c), List.of());
    }

    public static boolean isNeighbor(Character c) {
        return c != null && c.getRole() == CharacterRole.NEIGHBOR_FARMER && c.getStatus() == CharacterStatus.ACTIVE;
    }

    /** The neighbour who owns the farmland in the tool (T-21 mapping), if it is an active neighbour. */
    public Optional<Character> ownerOf(Savegame sg, int farmlandId) {
        return ownership.get(sg, farmlandId).filter(o -> o.getOwnerType() == OwnerType.CHARACTER)
                .map(FarmlandOwnership::getOwnerCharacter).filter(NeighborService::isNeighbor);
    }

    // ------------------------------------------------------------------------------------------ prices

    /**
     * Price per price unit (1000 l): best current price of the sell points, else the configured reference price;
     * empty when neither exists.
     */
    public OptionalDouble basePrice(FarmFacts f, String fillType) {
        Double best = f == null || f.prices() == null ? null : FactsService.bestPrices(f).get(fillType);
        if (best != null && best > 0) {
            return OptionalDouble.of(best);
        }
        Double reference = cfg().getReferencePrices().get(fillType);
        return reference == null || reference <= 0 ? OptionalDouble.empty() : OptionalDouble.of(reference);
    }

    /** Trust as capped bonus/malus like the negotiation engine: clamp(trust / divisor, -cap, +cap). */
    public double trustAdjustment(double trustScore) {
        double v = trustScore / cfg().getTrustDivisor();
        return Math.max(-cfg().getTrustCap(), Math.min(cfg().getTrustCap(), v));
    }

    /** Price per price unit when the neighbour sells to the player (good trust = cheaper). */
    public OptionalDouble sellUnitPrice(FarmFacts f, Character neighbor, String fillType) {
        OptionalDouble base = basePrice(f, fillType);
        if (base.isEmpty()) {
            return base;
        }
        double adj = trustAdjustment(trust.getCurrentTrust(neighbor));
        return OptionalDouble.of(base.getAsDouble() * cfg().getNeighborSellShare() * (1 - adj));
    }

    /** Price per price unit when the neighbour buys from the player (good trust = he pays more). */
    public OptionalDouble buyUnitPrice(FarmFacts f, Character neighbor, String fillType) {
        OptionalDouble base = basePrice(f, fillType);
        if (base.isEmpty()) {
            return base;
        }
        double adj = trustAdjustment(trust.getCurrentTrust(neighbor));
        return OptionalDouble.of(base.getAsDouble() * cfg().getNeighborBuyShare() * (1 + adj));
    }

    /** Total price of an amount in litres (rounded to whole euros). */
    public long total(double unitPrice, long liters) {
        return Math.round(unitPrice * liters / props.getFormulas().getStorage().getPriceUnitLiters());
    }

    // ------------------------------------------------------------------------------------------ player's silos

    /** R3-H2: the player's own silo goods; empty map when the mod does not export them (older mod). */
    public Map<String, BridgeDtos.TradeStorageEntry> tradeStorage(FarmFacts f) {
        Map<String, BridgeDtos.TradeStorageEntry> m = new LinkedHashMap<>();
        if (f == null || f.tradeStorage() == null) {
            return m;
        }
        for (BridgeDtos.TradeStorageEntry e : f.tradeStorage()) {
            if (e != null && e.fillType() != null) {
                m.put(e.fillType(), e);
            }
        }
        return m;
    }

    public Optional<FarmFacts> latest(Savegame sg) {
        return facts.latest(sg);
    }

    // ------------------------------------------------------------------------------------------ stock

    public List<NeighborStock> stock(Character c) {
        return stocks.findByCharacterOrderByFillTypeAsc(c).stream().filter(s -> s.getAmount() >= 1).toList();
    }

    public double stockOf(Character c, String fillType) {
        return stocks.findByCharacterAndFillType(c, fillType).map(NeighborStock::getAmount).orElse(0.0);
    }

    /** Adds (or with a negative amount takes) litres; never below 0. */
    @Transactional
    public double addStock(Savegame sg, Character c, String fillType, double liters) {
        NeighborStock s = stocks.findByCharacterAndFillType(c, fillType).orElseGet(() -> {
            NeighborStock n = new NeighborStock();
            n.setSavegame(sg);
            n.setCharacter(c);
            n.setFillType(fillType);
            return n;
        });
        s.setAmount(Math.max(0, s.getAmount() + liters));
        s.setUpdatedGameTime(sg.getCurrentGameTime());
        stocks.save(s);
        return s.getAmount();
    }

    /** The stock sinks every game month (sales, own use). */
    @EventListener
    @Order(80)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        double keep = 1 - cfg().getMonthlyDecay();
        for (NeighborStock s : stocks.findBySavegameOrderByIdAsc(sg)) {
            double amount = s.getAmount() * keep;
            if (amount < 1) {
                stocks.delete(s);
            } else {
                s.setAmount(amount);
                s.setUpdatedGameTime(e.gameTime());
            }
        }
    }
}
