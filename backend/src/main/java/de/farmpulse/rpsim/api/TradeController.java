package de.farmpulse.rpsim.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.Views.CaseView;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NeighborStock;
import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.neighbor.NeighborMissionService;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.neighbor.NpcFieldService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-H: app "Handel" (owner decision) - the neighbours with role, stock and needs, the own silo goods, the
 * offers, requests and contracts of the neighbours. Accept / decline go through {@code /api/cases/{id}/accept|decline}.
 */
@RestController
public class TradeController {

    /** One fill type of a neighbour's stock; {@code unitPrice} = his price per 1000 l for the player (null = none). */
    public record StockView(String fillType, long amount, Long unitPrice) {
    }

    public record NeighborView(Long id, String name, String role, String trustLevel, List<StockView> stock,
                               List<String> needs, List<Integer> farmlands) {
    }

    /** One fill type of the own silos (farm_facts.tradeStorage). */
    public record SiloView(String fillType, long amount, long freeCapacity) {
    }

    /**
     * {@code silosTracked} = the mod reports the own silos (else no trade); {@code fieldsTracked} = it reports the
     * neighbour fields (else no stock from harvests and no contracts); {@code missionLimitReached} of the game.
     */
    public record TradeView(boolean silosTracked, boolean fieldsTracked, Boolean missionLimitReached,
                            List<SiloView> silos, List<NeighborView> neighbors, List<CaseView> cases) {
    }

    public record GoodsRequest(@NotBlank String fillType, @NotNull @Positive Long amount) {
    }

    /** Roadmap V3.1 R31-A3: one own stable with its subtypes, free places and the game value per animal. */
    public record StableView(String husbandryUniqueId, String type, int count, Integer freeSlots,
                             List<BridgeDtos.SubTypeCount> subTypes, List<String> supportedSubTypes, Long valuePerAnimal) {
    }

    /** One animal type of a neighbour: his stock and his prices per animal (null = no value known). */
    public record AnimalStockView(String type, int count, Long sellUnitPrice, Long buyUnitPrice) {
    }

    public record AnimalNeighborView(Long id, String name, String role, String trustLevel, List<AnimalStockView> animals) {
    }

    /** {@code tracked} = the mod reports the stables with their subtypes (else no livestock trade). */
    public record AnimalTradeView(boolean tracked, int countMin, int countMax, List<StableView> stables,
                                  List<AnimalNeighborView> neighbors, List<CaseView> cases) {
    }

    public record AnimalRequest(@NotBlank String husbandryUniqueId, @NotBlank String subType, @NotNull @Positive Integer count) {
    }

    private final SavegameContext context;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final NeighborMissionService missions;
    private final NpcFieldService fields;
    private final de.farmpulse.rpsim.neighbor.LivestockTradeService livestock;
    private final de.farmpulse.rpsim.config.RpsimProperties props;
    private final ApiMapper mapper;

    public TradeController(SavegameContext context, NeighborService neighbors, NeighborTradeService trade,
                           NeighborMissionService missions, NpcFieldService fields,
                           de.farmpulse.rpsim.neighbor.LivestockTradeService livestock,
                           de.farmpulse.rpsim.config.RpsimProperties props, ApiMapper mapper) {
        this.livestock = livestock;
        this.props = props;
        this.context = context;
        this.neighbors = neighbors;
        this.trade = trade;
        this.missions = missions;
        this.fields = fields;
        this.mapper = mapper;
    }

    @GetMapping("/api/trade")
    @Transactional
    public TradeView trade() {
        Savegame sg = context.requireActive();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        List<SiloView> silos = ts.values().stream().map(e -> new SiloView(e.fillType(),
                Math.round(e.amount() == null ? 0 : e.amount()), Math.round(e.freeCapacity() == null ? 0 : e.freeCapacity())))
                .toList();
        List<NpcFieldRecord> records = fields.records(sg);
        List<NeighborView> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            List<StockView> stock = new ArrayList<>();
            for (NeighborStock s : neighbors.stock(n)) {
                var unit = neighbors.sellUnitPrice(f, n, s.getFillType());
                stock.add(new StockView(s.getFillType(), (long) Math.floor(s.getAmount()),
                        unit.isPresent() ? Math.round(unit.getAsDouble()) : null));
            }
            List<Integer> farmlands = records.stream().map(NpcFieldRecord::getFarmlandId)
                    .filter(id -> neighbors.ownerOf(sg, id).map(o -> o.getId().equals(n.getId())).orElse(false)).toList();
            list.add(new NeighborView(n.getId(), n.getName(), n.getNeighborRole(), mapper.trustLevel(n), stock,
                    neighbors.needs(n), farmlands));
        }
        List<ServiceCase> all = new ArrayList<>(trade.cases(sg));
        all.addAll(missions.cases(sg));
        all.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        return new TradeView(f != null && f.tradeStorage() != null, f != null && f.npcFields() != null,
                f == null ? null : f.missionLimitReached(), silos, list, all.stream().map(mapper::serviceCase).toList());
    }

    /** R3-H3: "Ware anfragen" - the neighbour answers at once with his price. */
    @PostMapping("/api/trade/neighbors/{id}/request")
    @Transactional
    public CaseView requestGoods(@PathVariable Long id, @Valid @RequestBody GoodsRequest r) {
        return mapper.serviceCase(trade.requestGoods(context.requireActive(), id, r.fillType(), r.amount()));
    }

    /** R3-H5 (optional in the roadmap, owner decision: yes): ask a neighbour for work on his fields. */
    @PostMapping("/api/trade/neighbors/{id}/work")
    @Transactional
    public CaseView askForWork(@PathVariable Long id) {
        return mapper.serviceCase(missions.askForWork(context.requireActive(), id));
    }

    // ------------------------------------------------------------------------------------------ R31-A3 livestock trade

    private static Long round(java.util.OptionalDouble v) {
        return v.isPresent() ? Math.round(v.getAsDouble()) : null;
    }

    /** R31-A3: own stables, the neighbours' animals and prices, the livestock offers and requests. */
    @GetMapping("/api/trade/animals")
    @Transactional
    public AnimalTradeView animals() {
        Savegame sg = context.requireActive();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        var stables = livestock.stables(f);
        List<StableView> stableViews = stables.stream().map(s -> new StableView(s.husbandryUniqueId(), s.type(), s.count(),
                s.freeSlots(), s.subTypes(), s.supportedSubTypes(), round(livestock.valuePerAnimal(f, s.type())))).toList();
        List<AnimalNeighborView> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            List<AnimalStockView> animals = new ArrayList<>();
            for (String type : livestock.animalTypes(n)) {
                animals.add(new AnimalStockView(type, livestock.stockOf(sg, n, type),
                        round(livestock.sellUnitPrice(f, n, type)), round(livestock.buyUnitPrice(f, n, type))));
            }
            list.add(new AnimalNeighborView(n.getId(), n.getName(), n.getNeighborRole(), mapper.trustLevel(n), animals));
        }
        var cfg = props.getFormulas().getLivestockTrade();
        return new AnimalTradeView(!stables.isEmpty(), cfg.getCountMin(), cfg.getCountMax(), stableViews, list,
                livestock.cases(sg).stream().map(mapper::serviceCase).toList());
    }

    /** R31-A3: "Tiere anfragen" - the neighbour answers at once with his price. */
    @PostMapping("/api/trade/neighbors/{id}/animals/request")
    @Transactional
    public CaseView requestAnimals(@PathVariable Long id, @Valid @RequestBody AnimalRequest r) {
        return mapper.serviceCase(livestock.requestAnimals(context.requireActive(), id, r.husbandryUniqueId(), r.subType(),
                r.count()));
    }

    /** R31-A3: "Tiere anbieten" - the neighbour answers at once with his price. */
    @PostMapping("/api/trade/neighbors/{id}/animals/offer")
    @Transactional
    public CaseView offerAnimals(@PathVariable Long id, @Valid @RequestBody AnimalRequest r) {
        return mapper.serviceCase(livestock.offerAnimals(context.requireActive(), id, r.husbandryUniqueId(), r.subType(),
                r.count()));
    }
}
