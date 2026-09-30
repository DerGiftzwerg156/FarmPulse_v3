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

    private final SavegameContext context;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final NeighborMissionService missions;
    private final NpcFieldService fields;
    private final ApiMapper mapper;

    public TradeController(SavegameContext context, NeighborService neighbors, NeighborTradeService trade,
                           NeighborMissionService missions, NpcFieldService fields, ApiMapper mapper) {
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
}
