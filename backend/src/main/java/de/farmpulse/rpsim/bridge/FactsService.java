package de.farmpulse.rpsim.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Read access to the stored raw facts (latest snapshot, market context) and raw-value aggregations. */
@Service
public class FactsService {

    private final FactsSnapshotRepository snapshots;
    private final JsonMapper json;
    private final RpsimProperties props;

    public FactsService(FactsSnapshotRepository snapshots, JsonMapper json, RpsimProperties props) {
        this.snapshots = snapshots;
        this.json = json;
        this.props = props;
    }

    public FarmFacts parse(FactsSnapshot s) {
        return json.readValue(s.getRawJson(), FarmFacts.class);
    }

    public Optional<FarmFacts> latest(Savegame sg) {
        return snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).map(this::parse);
    }

    public Optional<MarketContext> marketContext(Savegame sg) {
        if (sg.getMarketContextJson() == null) {
            return Optional.empty();
        }
        return Optional.of(json.readValue(sg.getMarketContextJson(), MarketContext.class));
    }

    /** Highest current price per fill type over all sell points (per price unit, e.g. 1000 l). */
    public static Map<String, Double> bestPrices(FarmFacts f) {
        Map<String, Double> best = new HashMap<>();
        for (BridgeDtos.Price p : f.prices()) {
            best.merge(p.fillType(), p.currentPrice(), Math::max);
        }
        return best;
    }

    /**
     * Technical concept "Warenbestand-Bewertung": storageValue = Σ(amount · bestAvailablePrice) per fill type,
     * bestAvailablePrice = highest currentPrice of that fill type. Prices are per priceUnitLiters.
     */
    public double storageValue(FarmFacts f) {
        Map<String, Double> best = bestPrices(f);
        double unit = props.getFormulas().getStorage().getPriceUnitLiters();
        double sum = 0;
        for (BridgeDtos.StorageEntry s : f.assets().storage()) {
            sum += s.amount() * best.getOrDefault(s.fillType(), 0.0) / unit;
        }
        return sum;
    }

    /** Asset sum: machines, buildings, land, animals and silo stock (functional concept "Datenbasis"). */
    public double totalAssetValue(FarmFacts f) {
        var a = f.assets();
        double v = a.vehicles().stream().mapToDouble(BridgeDtos.Vehicle::value).sum();
        v += a.placeables().stream().mapToDouble(BridgeDtos.Placeable::value).sum();
        v += a.farmland().stream().mapToDouble(BridgeDtos.OwnedFarmland::price).sum();
        v += a.animals().stream().mapToDouble(BridgeDtos.Animal::estimatedValue).sum();
        v += storageValue(f);
        return v;
    }

    /** Average vehicle condition 0..100 (live reading for workingConditions); neutral 70 without vehicles. */
    public static double averageCondition(FarmFacts f, double neutral) {
        List<BridgeDtos.Vehicle> v = f.assets().vehicles();
        if (v.isEmpty()) {
            return neutral;
        }
        return v.stream().mapToDouble(BridgeDtos.Vehicle::condition).average().orElse(neutral);
    }

    public double vanillaLoanRemaining(FarmFacts f) {
        var l = f.liabilities().vanillaLoan();
        return Boolean.TRUE.equals(l.active()) ? l.remainingAmount() : 0;
    }
}
