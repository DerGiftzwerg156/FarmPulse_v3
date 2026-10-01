package de.farmpulse.rpsim.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.RainPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FieldCropHistoryRepository;
import de.farmpulse.rpsim.repository.FieldRecordRepository;
import de.farmpulse.rpsim.repository.RainPeriodRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-C1 / R2-C2: keeps one {@link FieldRecord} per own field with the derived growth phase, the crop history
 * per FS25 year and the rain time per game month. The pure helpers (phase, growth progress, crop value) are used by the
 * damages (C3), the bank (C5) and the village reactions (C4 / C6).
 */
@Service
public class FieldService {

    private final SavegameRepository savegames;
    private final FieldRecordRepository records;
    private final FieldCropHistoryRepository history;
    private final RainPeriodRepository rain;
    private final FactsService facts;
    private final RpsimProperties props;
    private final GameTime gameTime;
    private final ApplicationEventPublisher publisher;

    public FieldService(SavegameRepository savegames, FieldRecordRepository records, FieldCropHistoryRepository history,
                        RainPeriodRepository rain, FactsService facts, RpsimProperties props, GameTime gameTime,
                        ApplicationEventPublisher publisher) {
        this.savegames = savegames;
        this.records = records;
        this.history = history;
        this.rain = rain;
        this.facts = facts;
        this.props = props;
        this.gameTime = gameTime;
        this.publisher = publisher;
    }

    private RpsimProperties.Fields cfg() {
        return props.getFormulas().getFields();
    }

    // ------------------------------------------------------------------------------------------ pure helpers

    /**
     * Growth phase: no crop = EMPTY; the flags of the mod (FS25 getIsWithered / getIsCut) decide WITHERED and
     * HARVESTED; otherwise below minHarvestingGrowthState = GROWING, up to max = HARVESTABLE, above max = WITHERED
     * (roadmap rule for mods without the flags).
     */
    public static FieldPhase phase(BridgeDtos.Field f) {
        if (f.fruitType() == null || f.fruitType().isBlank()) {
            return FieldPhase.EMPTY;
        }
        if (Boolean.TRUE.equals(f.withered())) {
            return FieldPhase.WITHERED;
        }
        if (Boolean.TRUE.equals(f.cut())) {
            return FieldPhase.HARVESTED;
        }
        int gs = f.growthState() == null ? 0 : f.growthState();
        Integer min = f.minHarvestingGrowthState();
        Integer max = f.maxHarvestingGrowthState();
        if (min == null || gs < min) {
            return FieldPhase.GROWING;
        }
        if (max == null || gs <= max) {
            return FieldPhase.HARVESTABLE;
        }
        return FieldPhase.WITHERED;
    }

    /** Growth progress 0..1 of a standing crop (growthState / minHarvestingGrowthState, harvestable = 1). */
    public static double progress(BridgeDtos.Field f) {
        FieldPhase p = phase(f);
        if (p == FieldPhase.HARVESTABLE) {
            return 1;
        }
        if (p != FieldPhase.GROWING) {
            return 0;
        }
        Integer min = f.minHarvestingGrowthState();
        int gs = f.growthState() == null ? 0 : f.growthState();
        return min == null || min <= 0 ? 1 : Math.max(0, Math.min(1, gs / (double) min));
    }

    /** Yield in liters per m²: exported by the mod, otherwise the configured fallback per fruit type. */
    public OptionalDouble litersPerSqm(BridgeDtos.Field f) {
        if (f.litersPerSqm() != null && f.litersPerSqm() > 0) {
            return OptionalDouble.of(f.litersPerSqm());
        }
        Double configured = f.fruitType() == null ? null : cfg().getYieldLitersPerSqm().get(f.fruitType());
        return configured == null || configured <= 0 ? OptionalDouble.empty() : OptionalDouble.of(configured);
    }

    /**
     * Value of the full harvest of a field: area x yield x best current price of its fill type (per price unit).
     * Empty when the yield or the price is unknown.
     */
    public OptionalDouble harvestValue(BridgeDtos.Field f, Map<String, Double> bestPrices) {
        OptionalDouble yield = litersPerSqm(f);
        String fillType = f.fillType() != null ? f.fillType() : f.fruitType();
        Double price = fillType == null ? null : bestPrices.get(fillType);
        if (yield.isEmpty() || price == null || price <= 0 || f.hectares() == null) {
            return OptionalDouble.empty();
        }
        double liters = f.hectares() * 10_000 * yield.getAsDouble();
        return OptionalDouble.of(liters * price / props.getFormulas().getStorage().getPriceUnitLiters());
    }

    /** R2-C5: one standing crop in the credit check - harvest value x growth progress x discount. */
    public record StandingCrop(BridgeDtos.Field field, double value) {
    }

    public List<StandingCrop> standingCrops(FarmFacts f, double discount) {
        if (f == null || f.fields() == null) {
            return List.of();
        }
        Map<String, Double> best = FactsService.bestPrices(f);
        List<StandingCrop> list = new ArrayList<>();
        for (BridgeDtos.Field field : f.fields()) {
            if (field == null || !phase(field).standing()) {
                continue;
            }
            OptionalDouble value = harvestValue(field, best);
            if (value.isPresent()) {
                list.add(new StandingCrop(field, value.getAsDouble() * progress(field) * discount));
            }
        }
        list.sort(Comparator.comparingDouble(StandingCrop::value).reversed());
        return list;
    }

    // ------------------------------------------------------------------------------------------ C2 rain

    /** Rain share (0..1) of a game month; 0 without samples. */
    public double rainShare(Savegame sg, long monthIndex) {
        return rain.findBySavegameAndMonthIndex(sg, monthIndex).map(RainPeriod::rainShare).orElse(0.0);
    }

    public Optional<RainPeriod> rainPeriod(Savegame sg, long monthIndex) {
        return rain.findBySavegameAndMonthIndex(sg, monthIndex);
    }

    /**
     * Sample and hold: the game time since the last sample counts as rain when the last sample said "raining". Gaps
     * above rain-sample-max-gap-minutes (backend was off) and a rewound game time only move the reference point.
     */
    void sampleRain(Savegame sg, BridgeDtos.Weather w, long now) {
        if (w == null || w.raining() == null) {
            sg.setLastWeatherGameTime(null);
            sg.setLastWeatherRaining(null);
            return;
        }
        Long last = sg.getLastWeatherGameTime();
        if (last != null && now > last && now - last <= GameTime.hours(cfg().getRainSampleMaxGapMinutes() / 60.0)) {
            long month = gameTime.monthIndex(sg, now);
            RainPeriod rp = rain.findBySavegameAndMonthIndex(sg, month).orElseGet(() -> {
                RainPeriod p = new RainPeriod();
                p.setSavegame(sg);
                p.setMonthIndex(month);
                return p;
            });
            long elapsed = now - last;
            rp.setObservedMs(rp.getObservedMs() + elapsed);
            if (Boolean.TRUE.equals(sg.getLastWeatherRaining())) {
                rp.setRainMs(rp.getRainMs() + elapsed);
            }
            rain.save(rp);
        }
        sg.setLastWeatherGameTime(now);
        sg.setLastWeatherRaining(w.raining());
    }

    // ------------------------------------------------------------------------------------------ C1 records

    @EventListener
    @Order(5)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null) {
            return;
        }
        sampleRain(sg, f.weather(), e.gameTime());
        if (f.fields() == null) {
            sg.setFieldsTracked(false);
            return;
        }
        sg.setFieldsTracked(true);
        Integer year = f.calendar() == null ? null : f.calendar().year();
        update(sg, f, e.gameTime(), year);
        if (year != null) {
            Integer before = sg.getFieldYear();
            sg.setFieldYear(year);
            if (before != null && year > before) {
                publisher.publishEvent(new FieldYearClosedEvent(sg.getId(), before));
            }
        }
    }

    /** Updates the records of the exported fields; fields no longer exported (sold) are dropped. */
    @Transactional
    public void update(Savegame sg, FarmFacts f, long now, Integer year) {
        Map<Integer, FieldRecord> byFarmland = new HashMap<>();
        for (FieldRecord r : records.findBySavegameOrderByFarmlandIdAsc(sg)) {
            byFarmland.put(r.getFarmlandId(), r);
        }
        BridgeDtos.FieldRules rules = f.fieldRules();
        for (BridgeDtos.Field field : f.fields()) {
            if (field == null || field.farmlandId() == null) {
                continue;
            }
            FieldRecord r = byFarmland.remove(field.farmlandId());
            FieldPhase p = phase(field);
            if (r == null) {
                r = new FieldRecord();
                r.setSavegame(sg);
                r.setFarmlandId(field.farmlandId());
                r.setPhase(p);
                r.setPhaseSinceGameTime(now);
            } else if (now < r.getLastSeenGameTime()) {
                rewind(r, now); // reload without saving: nothing may lie in the future
            }
            if (year != null && field.fruitType() != null) {
                FieldCropHistory h = crop(sg, field.farmlandId(), year, field.fruitType(), now);
                h.setHarvestableSeen(h.isHarvestableSeen() || p == FieldPhase.HARVESTABLE);
                h.setWithered(h.isWithered() || p == FieldPhase.WITHERED);
                if (p == FieldPhase.HARVESTABLE && field.hectares() != null && field.litersPerSqm() != null) {
                    // R3-K3: expected yield of the last ripe sighting (area x litres per m²)
                    h.setRipeLiters(field.hectares() * 10_000 * field.litersPerSqm());
                }
                if (p == FieldPhase.HARVESTED) {
                    harvested(h);
                }
            }
            if (p != r.getPhase()) {
                if (r.getPhase() == FieldPhase.HARVESTABLE && p.bare() && year != null && r.getFruitType() != null) {
                    // harvested without a stubble state (crop gone): the previous crop counts as harvested
                    harvested(crop(sg, r.getFarmlandId(), year, r.getFruitType(), now));
                }
                if (r.getPhase() == FieldPhase.HARVESTABLE) {
                    r.setHarvestHintSent(false);
                }
                if (!p.bare()) {
                    r.setFallowGossipSent(false);
                }
                if (p != FieldPhase.WITHERED) {
                    r.setWitheredGossipSent(false);
                }
                if (!(r.getPhase().bare() && p.bare())) {
                    r.setPhaseSinceGameTime(now); // EMPTY <-> HARVESTED stays one fallow episode
                }
                r.setPhase(p);
            }
            // weeds / stones matter only when the savegame has them switched on (unknown without fieldRules)
            boolean weeds = rules != null && Boolean.TRUE.equals(rules.weedsEnabled())
                    && field.weedState() != null && field.weedState() >= cfg().getWeedHighState();
            boolean stones = rules != null && Boolean.TRUE.equals(rules.stonesEnabled())
                    && field.stoneLevel() != null && field.stoneLevel() >= cfg().getStoneHighLevel();
            r.setWeedsHighSince(weeds ? (r.getWeedsHighSince() == null ? now : r.getWeedsHighSince()) : null);
            r.setStonesHighSince(stones ? (r.getStonesHighSince() == null ? now : r.getStonesHighSince()) : null);
            if (!weeds && !stones) {
                r.setNeighborWarnings(0);
            }
            if (field.limeLevel() != null && field.limeLevel() > 0) {
                r.setLimeHintSent(false);
            }
            if (field.plowLevel() != null && field.plowLevel() > 0) {
                r.setPlowHintSent(false);
            }
            r.setFieldName(field.name());
            r.setHectares(field.hectares());
            r.setFruitType(field.fruitType());
            r.setLastSeenGameTime(now);
            records.save(r);
        }
        records.deleteAll(byFarmland.values());
    }

    /** The crop was harvested; R3-K3: its yield is the last ripe sighting (owner decision, recorded from now on). */
    private static void harvested(FieldCropHistory h) {
        h.setHarvested(true);
        if (h.getYieldLiters() == null && h.getRipeLiters() != null) {
            h.setYieldLiters(h.getRipeLiters());
        }
    }

    private static void rewind(FieldRecord r, long now) {
        r.setPhaseSinceGameTime(Math.min(r.getPhaseSinceGameTime(), now));
        if (r.getWeedsHighSince() != null && r.getWeedsHighSince() > now) {
            r.setWeedsHighSince(now);
        }
        if (r.getStonesHighSince() != null && r.getStonesHighSince() > now) {
            r.setStonesHighSince(now);
        }
        if (r.getLastNeighborWarningGameTime() != null && r.getLastNeighborWarningGameTime() > now) {
            r.setLastNeighborWarningGameTime(now);
        }
    }

    private FieldCropHistory crop(Savegame sg, int farmlandId, int year, String fruitType, long now) {
        return history.findBySavegameAndFarmlandIdAndCropYearAndFruitType(sg, farmlandId, year, fruitType)
                .orElseGet(() -> {
                    FieldCropHistory h = new FieldCropHistory();
                    h.setSavegame(sg);
                    h.setFarmlandId(farmlandId);
                    h.setCropYear(year);
                    h.setFruitType(fruitType);
                    h.setFirstSeenGameTime(now);
                    return history.save(h);
                });
    }

    public List<FieldRecord> records(Savegame sg) {
        return records.findBySavegameOrderByFarmlandIdAsc(sg);
    }

    public List<FieldCropHistory> cropsOfYear(Savegame sg, int year) {
        return history.findBySavegameAndCropYear(sg, year);
    }
}
