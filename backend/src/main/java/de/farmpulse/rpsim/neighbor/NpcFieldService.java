package de.farmpulse.rpsim.neighbor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.NpcFieldCrop;
import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.NpcFieldCropRepository;
import de.farmpulse.rpsim.repository.NpcFieldRecordRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-H1: the fields the game's NPCs farm (farm_facts.npcFields) are kept like the own fields - growth phase
 * and crop per FS25 year. A harvest (phase HARVESTED after HARVESTABLE) puts harvest-share of the yield (area x
 * litersPerSqm) into the stock of the neighbour who owns the farmland in the tool, cereals also straw (R3-H2).
 * Fallback of the 🟡 point of R3-H1: the stock works with the last crop seen, whatever the game does with the fields.
 */
@Service
public class NpcFieldService {

    private static final Logger log = LoggerFactory.getLogger(NpcFieldService.class);

    private final SavegameRepository savegames;
    private final NpcFieldRecordRepository records;
    private final NpcFieldCropRepository crops;
    private final FactsService facts;
    private final NeighborService neighbors;
    private final RpsimProperties props;

    public NpcFieldService(SavegameRepository savegames, NpcFieldRecordRepository records, NpcFieldCropRepository crops,
                           FactsService facts, NeighborService neighbors, RpsimProperties props) {
        this.savegames = savegames;
        this.records = records;
        this.crops = crops;
        this.facts = facts;
        this.neighbors = neighbors;
        this.props = props;
    }

    /** Harvest of one neighbour field credited to a neighbour's stock. */
    public record Harvest(Character neighbor, String fillType, double liters, String byProduct, double byProductLiters) {
    }

    @EventListener
    @Order(6)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.npcFields() == null) {
            return; // older mod or switched off (npcFieldExport): nothing known about the neighbour fields
        }
        Integer year = f.calendar() == null ? null : f.calendar().year();
        update(sg, f.npcFields(), e.gameTime(), year);
    }

    /** Updates the records; returns the harvests credited to neighbours. Fields no longer exported are dropped. */
    @Transactional
    public List<Harvest> update(Savegame sg, List<BridgeDtos.Field> fields, long now, Integer year) {
        Map<Integer, NpcFieldRecord> byFarmland = new HashMap<>();
        for (NpcFieldRecord r : records.findBySavegameOrderByFarmlandIdAsc(sg)) {
            byFarmland.put(r.getFarmlandId(), r);
        }
        List<Harvest> harvests = new java.util.ArrayList<>();
        for (BridgeDtos.Field field : fields) {
            if (field == null || field.farmlandId() == null) {
                continue;
            }
            NpcFieldRecord r = byFarmland.remove(field.farmlandId());
            FieldPhase p = FieldService.phase(field);
            boolean rewound = false;
            if (r == null) {
                r = new NpcFieldRecord();
                r.setSavegame(sg);
                r.setFarmlandId(field.farmlandId());
                r.setPhase(p);
                r.setPhaseSinceGameTime(now);
            } else if (now < r.getLastSeenGameTime()) {
                rewound = true; // reload without saving: no harvest is credited twice
                r.setPhaseSinceGameTime(Math.min(r.getPhaseSinceGameTime(), now));
            }
            if (year != null && field.fruitType() != null) {
                crop(sg, field.farmlandId(), year, field.fruitType(), now);
            }
            if (p != r.getPhase()) {
                boolean harvested = r.getPhase() == FieldPhase.HARVESTABLE
                        && (p == FieldPhase.HARVESTED || p == FieldPhase.EMPTY);
                if (harvested && !rewound) {
                    harvest(sg, r, year, now).ifPresent(harvests::add);
                }
                r.setPhase(p);
                r.setPhaseSinceGameTime(now);
            }
            r.setFieldName(field.name());
            r.setHectares(field.hectares());
            r.setPlowLevel(field.plowLevel());
            r.setStoneLevel(field.stoneLevel());
            if (field.fruitType() != null) {
                // keep the crop details of the last crop: after the harvest the stubble may not carry them
                r.setFruitType(field.fruitType());
                r.setFillType(field.fillType());
                r.setLitersPerSqm(field.litersPerSqm());
            }
            r.setLastSeenGameTime(now);
            records.save(r);
        }
        records.deleteAll(byFarmland.values());
        return harvests;
    }

    /** harvest-share of area x litersPerSqm into the owner's stock, plus the by-product (straw) of cereals. */
    Optional<Harvest> harvest(Savegame sg, NpcFieldRecord r, Integer year, long now) {
        if (year != null && r.getFruitType() != null) {
            crop(sg, r.getFarmlandId(), year, r.getFruitType(), now).setHarvested(true);
        }
        Optional<Character> owner = neighbors.ownerOf(sg, r.getFarmlandId());
        Double lps = r.getLitersPerSqm();
        if (owner.isEmpty() || r.getHectares() == null || lps == null || lps <= 0) {
            return Optional.empty();
        }
        RpsimProperties.NeighborTrade cfg = props.getFormulas().getNeighborTrade();
        String fillType = r.getFillType() != null ? r.getFillType() : r.getFruitType();
        double liters = r.getHectares() * 10_000 * lps * cfg.getHarvestShare();
        neighbors.addStock(sg, owner.get(), fillType, liters);
        String byProduct = r.getFruitType() == null ? null : cfg.getByProducts().get(r.getFruitType());
        double byLiters = 0;
        if (byProduct != null) {
            byLiters = liters * cfg.getByProductShare();
            neighbors.addStock(sg, owner.get(), byProduct, byLiters);
        }
        log.info("Neighbour {} harvested field {}: +{} l {}{}", owner.get().getName(), r.getFarmlandId(),
                Math.round(liters), fillType, byProduct == null ? "" : ", +" + Math.round(byLiters) + " l " + byProduct);
        return Optional.of(new Harvest(owner.get(), fillType, liters, byProduct, byLiters));
    }

    private NpcFieldCrop crop(Savegame sg, int farmlandId, int year, String fruitType, long now) {
        return crops.findBySavegameAndFarmlandIdAndCropYearAndFruitType(sg, farmlandId, year, fruitType).orElseGet(() -> {
            NpcFieldCrop c = new NpcFieldCrop();
            c.setSavegame(sg);
            c.setFarmlandId(farmlandId);
            c.setCropYear(year);
            c.setFruitType(fruitType);
            c.setFirstSeenGameTime(now);
            return crops.save(c);
        });
    }

    public List<NpcFieldRecord> records(Savegame sg) {
        return records.findBySavegameOrderByFarmlandIdAsc(sg);
    }

    /** Crops of a neighbour field, newest FS25 year first. */
    public List<NpcFieldCrop> crops(Savegame sg, int farmlandId) {
        return crops.findBySavegameAndFarmlandIdOrderByCropYearDescIdDesc(sg, farmlandId);
    }
}
