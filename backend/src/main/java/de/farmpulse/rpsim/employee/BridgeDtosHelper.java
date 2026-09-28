package de.farmpulse.rpsim.employee;

import java.util.Objects;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.Husbandry;

/** Roadmap V2 R2-A7: averages over the husbandries of farm_facts (null when there is nothing to average). */
final class BridgeDtosHelper {

    private BridgeDtosHelper() {
    }

    static Double meanHealth(FarmFacts f) {
        if (f.husbandries() == null) {
            return null;
        }
        var values = f.husbandries().stream().filter(Objects::nonNull).map(Husbandry::health).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray();
        return values.length == 0 ? null : java.util.Arrays.stream(values).average().orElse(0);
    }

    static Double meanProductivity(FarmFacts f) {
        if (f.husbandries() == null) {
            return null;
        }
        var values = f.husbandries().stream().filter(Objects::nonNull).map(Husbandry::productivity)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        return values.length == 0 ? null : java.util.Arrays.stream(values).average().orElse(0);
    }
}
