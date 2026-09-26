package de.farmpulse.rpsim.bridge;

import java.util.ArrayList;
import java.util.List;

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
        return e;
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
