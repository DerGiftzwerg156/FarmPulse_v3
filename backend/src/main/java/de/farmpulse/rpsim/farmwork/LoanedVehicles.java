package de.farmpulse.rpsim.farmwork;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.MachineLoanRepository;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.1 R31-A2: borrowed and demo machines standing on the farm. They are in {@code assets.vehicles} (the game
 * owns them for the player farm) but no asset of the farm: not in the bank's assets, the depreciation, the maintenance
 * fee and repairs, the mechanic's work or a sale to a neighbour (owner decision 2026-10-02).
 */
@Component
public class LoanedVehicles {

    private static final List<String> ON_FARM = List.of(MachineLoan.ACTIVE, MachineLoan.RETURNING,
            MachineLoan.PURCHASE_OFFER);

    private final MachineLoanRepository loans;

    public LoanedVehicles(MachineLoanRepository loans) {
        this.loans = loans;
    }

    /** uniqueIds of the borrowed and demo machines on the farm. */
    public Set<String> ids(Savegame sg) {
        return loans.findBySavegameAndStatusInOrderByIdAsc(sg, ON_FARM).stream().map(MachineLoan::getVehicleId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
    }

    /** The farm's own vehicles of an export (without borrowed and demo machines). */
    public List<BridgeDtos.Vehicle> own(Savegame sg, List<BridgeDtos.Vehicle> vehicles) {
        if (vehicles == null) {
            return List.of();
        }
        Set<String> ids = ids(sg);
        return ids.isEmpty() ? vehicles : vehicles.stream().filter(v -> v == null || !ids.contains(v.uniqueId())).toList();
    }

    /** Game value of the borrowed and demo machines in an export. */
    public double value(Savegame sg, BridgeDtos.FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return 0;
        }
        Set<String> ids = ids(sg);
        return f.assets().vehicles().stream().filter(v -> v != null && ids.contains(v.uniqueId()))
                .mapToDouble(v -> v.value() == null ? 0 : v.value()).sum();
    }
}
