package de.farmpulse.rpsim.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import de.farmpulse.rpsim.bridge.BridgeDtos.AckDocument;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;

/**
 * Schema validation before processing (defensive against partially written files, analogous to the mod's
 * defensive parsing). Mirrors tools/bridge-simulator/schema/*.schema.json.
 */
public final class BridgeValidator {

    private BridgeValidator() {
    }

    public static List<String> validate(FarmFacts f) {
        List<String> e = new ArrayList<>();
        if (f.schemaVersion() == null || f.schemaVersion() != 1) e.add("schemaVersion must be 1");
        if (f.gameTime() == null || f.gameTime() < 0) e.add("gameTime missing/negative");
        if (blank(f.savegameId())) e.add("savegameId missing");
        if (f.liquidity() == null || f.liquidity().balance() == null) e.add("liquidity.balance missing");
        if (f.assets() == null) {
            e.add("assets missing");
        } else {
            var a = f.assets();
            if (a.vehicles() == null || a.placeables() == null || a.farmland() == null || a.animals() == null
                    || a.storage() == null) {
                e.add("assets.{vehicles,placeables,farmland,animals,storage} required");
            } else {
                a.vehicles().forEach(v -> {
                    if (v == null || blank(v.uniqueId()) || v.value() == null || v.condition() == null
                            || v.condition() < 0 || v.condition() > 100) e.add("invalid vehicle " + v);
                });
                a.farmland().forEach(fl -> {
                    if (fl == null || fl.farmlandId() == null || fl.price() == null) e.add("invalid farmland " + fl);
                });
                a.storage().forEach(s -> {
                    if (s == null || blank(s.fillType()) || s.amount() == null || s.amount() < 0) e.add("invalid storage " + s);
                });
                a.animals().forEach(an -> {
                    if (an == null || an.count() == null || an.estimatedValue() == null) e.add("invalid animal " + an);
                });
                a.placeables().forEach(p -> {
                    if (p == null || p.value() == null) e.add("invalid placeable " + p);
                });
            }
        }
        if (f.liabilities() == null || f.liabilities().vanillaLoan() == null
                || f.liabilities().vanillaLoan().active() == null
                || f.liabilities().vanillaLoan().remainingAmount() == null) {
            e.add("liabilities.vanillaLoan missing");
        }
        if (f.calendar() != null) {
            var c = f.calendar();
            if (c.period() == null || c.period() < 1 || c.period() > 12 || c.daysPerPeriod() == null || c.daysPerPeriod() < 1
                    || c.monotonicDay() == null || c.monotonicDay() < 0
                    || (c.dayInPeriod() != null && (c.dayInPeriod() < 1 || c.dayInPeriod() > c.daysPerPeriod()))) {
                e.add("invalid calendar " + c);
            }
        }
        if (f.prices() == null) {
            e.add("prices missing");
        } else {
            f.prices().forEach(p -> {
                if (p == null || blank(p.sellPoint()) || blank(p.fillType()) || p.currentPrice() == null) e.add("invalid price " + p);
            });
        }
        validateRoadmapV2(f, e);
        return e;
    }

    /** Roadmap V2 (R2-Q1): the optional blocks are only checked when present; a missing block is no error. */
    private static void validateRoadmapV2(FarmFacts f, List<String> e) {
        if (f.finances() != null) {
            if (f.finances().periods() == null) {
                e.add("finances.periods missing");
            } else {
                f.finances().periods().forEach(p -> {
                    if (p == null || p.year() == null || p.period() == null || p.period() < 1 || p.period() > 12
                            || p.byType() == null || p.byType().values().stream().anyMatch(Objects::isNull)) {
                        e.add("invalid finance period " + p);
                    }
                });
            }
        }
        if (f.workforce() != null) {
            var w = f.workforce();
            if (w.activeJobs() == null || w.workedGameMs() == null) {
                e.add("workforce.{activeJobs,workedGameMs} required");
            } else {
                w.activeJobs().forEach(j -> {
                    if (j == null || j.jobId() == null) e.add("invalid active job " + j);
                });
                w.workedGameMs().forEach((id, ms) -> {
                    if (ms == null || ms < 0) e.add("invalid workedGameMs of employee " + id);
                });
            }
        }
        if (f.husbandries() != null) {
            f.husbandries().forEach(h -> {
                if (h == null || blank(h.husbandryUniqueId()) || negativeOrNull(h.health()) || negativeOrNull(h.food())
                        || (h.productivity() != null && h.productivity() < 0) || h.conditions() == null
                        || h.conditions().stream().anyMatch(c -> c == null || c.title() == null || negativeOrNull(c.ratio()))) {
                    e.add("invalid husbandry " + h);
                }
            });
        }
        if (f.fields() != null) {
            f.fields().forEach(fd -> {
                if (fd == null || fd.farmlandId() == null || fd.name() == null || negativeOrNull(fd.hectares())
                        || Stream.of(fd.growthState(), fd.weedState(), fd.stoneLevel(), fd.sprayLevel(), fd.limeLevel(),
                        fd.plowLevel()).anyMatch(v -> v == null || v < 0)
                        || (fd.litersPerSqm() != null && fd.litersPerSqm() < 0)) {
                    e.add("invalid field " + fd);
                }
            });
        }
        if (f.fieldRules() != null) {
            var r = f.fieldRules();
            if (Stream.of(r.plowingRequired(), r.limeRequired(), r.weedsEnabled(), r.stonesEnabled()).anyMatch(v -> v == null)) {
                e.add("invalid fieldRules " + r);
            }
        }
        if (f.weather() != null) {
            var w = f.weather();
            if (w.raining() == null || negativeOrNull(w.rainFallScale()) || negativeOrNull(w.groundWetness())) {
                e.add("invalid weather " + w);
            }
        }
    }

    private static boolean negativeOrNull(Double v) {
        return v == null || v < 0;
    }

    public static List<String> validate(MarketContext m) {
        List<String> e = new ArrayList<>();
        if (blank(m.savegameId())) e.add("savegameId missing");
        if (m.sellPoints() == null) e.add("sellPoints missing");
        if (m.fillTypes() == null) e.add("fillTypes missing");
        if (m.farmlands() == null) {
            e.add("farmlands missing");
        } else {
            m.farmlands().forEach(f -> {
                if (f == null || f.farmlandId() == null || f.ownerFarmId() == null) e.add("invalid farmland " + f);
            });
        }
        return e;
    }

    public static List<String> validate(AckDocument a) {
        List<String> e = new ArrayList<>();
        if (blank(a.savegameId())) e.add("savegameId missing");
        if (a.acks() == null) e.add("acks missing");
        return e;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
