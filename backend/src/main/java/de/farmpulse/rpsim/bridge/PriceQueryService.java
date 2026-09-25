package de.farmpulse.rpsim.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.Views.PricePoint;
import de.farmpulse.rpsim.api.Views.PriceSeries;
import de.farmpulse.rpsim.api.Views.PriceView;
import de.farmpulse.rpsim.api.Views.StorageOverview;
import de.farmpulse.rpsim.api.Views.StorageView;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import org.springframework.stereotype.Service;

/**
 * Silo stock and market price overview from the FactsSnapshot time series - no extra entity needed; the price
 * history is a time-series query over the stored raw snapshots (down-sampled to a configurable point count).
 */
@Service
public class PriceQueryService {

    private final FactsService facts;
    private final FactsSnapshotRepository snapshots;
    private final RpsimProperties props;

    public PriceQueryService(FactsService facts, FactsSnapshotRepository snapshots, RpsimProperties props) {
        this.facts = facts;
        this.snapshots = snapshots;
        this.props = props;
    }

    private Map<String, String> sellPointNames(Savegame sg) {
        Map<String, String> names = new HashMap<>();
        facts.marketContext(sg).ifPresent(c -> c.sellPoints().forEach(s -> names.put(s.id(), s.name())));
        return names;
    }

    public StorageOverview storage(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null) {
            return new StorageOverview(sg.getCurrentGameTime(), 0, List.of());
        }
        Map<String, String> names = sellPointNames(sg);
        double unit = props.getFormulas().getStorage().getPriceUnitLiters();
        List<StorageView> items = new ArrayList<>();
        long total = 0;
        for (BridgeDtos.StorageEntry s : f.assets().storage()) {
            BridgeDtos.Price best = f.prices().stream().filter(p -> p.fillType().equals(s.fillType()))
                    .max(Comparator.comparingDouble(BridgeDtos.Price::currentPrice)).orElse(null);
            double price = best == null ? 0 : best.currentPrice();
            long value = Math.round(s.amount() * price / unit);
            total += value;
            items.add(new StorageView(s.fillType(), Math.round(s.amount()), Math.round(s.capacity()), price,
                    best == null ? null : names.getOrDefault(best.sellPoint(), best.sellPoint()), value));
        }
        return new StorageOverview(f.gameTime(), total, items);
    }

    public List<PriceView> current(Savegame sg) {
        Map<String, String> names = sellPointNames(sg);
        return facts.latest(sg).map(f -> f.prices().stream()
                .map(p -> new PriceView(p.sellPoint(), names.getOrDefault(p.sellPoint(), p.sellPoint()), p.fillType(),
                        p.currentPrice())).toList()).orElse(List.of());
    }

    /** Price history per sell point/fill type, optional filters, time range in game time. */
    public List<PriceSeries> history(Savegame sg, String fillType, String sellPoint, Long from, Long to) {
        long f = from == null ? 0 : from;
        long t = to == null ? Long.MAX_VALUE : to;
        List<FactsSnapshot> range = snapshots.findBySavegameAndGameTimeBetweenOrderByGameTimeAscIdAsc(sg, f, t);
        int max = props.getFormulas().getStorage().getHistoryMaxPoints();
        int step = Math.max(1, (int) Math.ceil(range.size() / (double) max));
        Map<String, String> names = sellPointNames(sg);
        Map<String, List<PricePoint>> series = new LinkedHashMap<>();
        for (int i = 0; i < range.size(); i++) {
            if (i % step != 0 && i != range.size() - 1) {
                continue;
            }
            FactsSnapshot s = range.get(i);
            for (BridgeDtos.Price p : facts.parse(s).prices()) {
                if ((fillType != null && !fillType.equals(p.fillType())) || (sellPoint != null && !sellPoint.equals(p.sellPoint()))) {
                    continue;
                }
                series.computeIfAbsent(p.sellPoint() + "|" + p.fillType(), k -> new ArrayList<>())
                        .add(new PricePoint(s.getGameTime(), p.currentPrice()));
            }
        }
        return series.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("\\|", 2);
            return new PriceSeries(k[0], names.getOrDefault(k[0], k[0]), k[1], e.getValue());
        }).toList();
    }
}
