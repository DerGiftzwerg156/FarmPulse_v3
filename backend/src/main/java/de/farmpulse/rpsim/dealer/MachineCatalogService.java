package de.farmpulse.rpsim.dealer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.AbsenceScale;
import org.springframework.stereotype.Service;

/**
 * Roadmap vehicle dealer RFH-K1 / K2: the dealer's catalog is the whole vehicle catalog of the shop (store_catalog.json,
 * owner decision 2026-10-15). The list is filtered by text, category, brand and price range, sorted by category and
 * name (or by name, price, power) and paged; one entry is put together with {@link MachineConfigurator} and gets the
 * expected delivery time ({@link DeliveryTime}).
 */
@Service
public class MachineCatalogService {

    /** One line of the catalog list. */
    public record Entry(String xmlFilename, String name, String brand, String brandTitle, String categoryName,
                        String categoryTitle, long price, long dailyUpkeep, Integer powerMin, Integer powerMax,
                        boolean isMod, Boolean motorized) {
    }

    /** A filter value: technical name and display name of the game (null when the mod sends none). */
    public record FilterValue(String name, String title) {
    }

    /** Filter of the list; null = no filter. {@code sort} = CATEGORY (default), NAME, PRICE or POWER. */
    public record Query(String text, String category, String brand, Long priceMin, Long priceMax, String sort,
                        boolean descending, int page) {
    }

    public record Page(List<Entry> items, int total, int page, int pageSize, List<FilterValue> categories,
                       List<FilterValue> brands) {
    }

    /** A machine put together, with the expected delivery. */
    public record Quote(MachineConfigurator.Result result, DeliveryTime.Estimate delivery) {
    }

    private record Cached(String json, BridgeDtos.StoreCatalog catalog) {
    }

    private final FactsService facts;
    private final RpsimProperties props;
    private final AbsenceScale absence;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public MachineCatalogService(FactsService facts, RpsimProperties props, AbsenceScale absence) {
        this.facts = facts;
        this.props = props;
        this.absence = absence;
    }

    private RpsimProperties.MachineDealer cfg() {
        return props.getFormulas().getMachineDealer();
    }

    /** The stored catalog of the savegame (parsed once per export of the mod). */
    public Optional<BridgeDtos.StoreCatalog> catalog(Savegame sg) {
        String json = sg.getStoreCatalogJson();
        if (json == null) {
            return Optional.empty();
        }
        Cached c = cache.get(sg.getId());
        if (c == null || !c.json().equals(json)) {
            c = new Cached(json, facts.storeCatalog(sg).orElseThrow());
            cache.put(sg.getId(), c);
        }
        return Optional.of(c.catalog());
    }

    public Optional<BridgeDtos.CatalogItem> item(Savegame sg, String xmlFilename) {
        return catalog(sg).flatMap(c -> c.items().stream().filter(i -> i.xmlFilename().equals(xmlFilename)).findFirst());
    }

    public BridgeDtos.CatalogItem requireItem(Savegame sg, String xmlFilename) {
        return item(sg, xmlFilename).orElseThrow(() -> new NotFoundException("Maschine nicht im Katalog: " + xmlFilename));
    }

    /** The list; empty while the mod sent no catalog (older mod or no game start yet). */
    public Optional<Page> list(Savegame sg, Query q) {
        return catalog(sg).map(c -> page(c.items(), q));
    }

    Page page(List<BridgeDtos.CatalogItem> all, Query q) {
        List<Entry> entries = all.stream().map(MachineCatalogService::entry).toList();
        List<FilterValue> categories = values(entries, Entry::categoryName, Entry::categoryTitle);
        List<FilterValue> brands = values(entries, Entry::brand, Entry::brandTitle);
        String text = q.text() == null ? "" : q.text().trim().toLowerCase(Locale.GERMAN);
        List<Entry> hits = new ArrayList<>(entries.stream()
                .filter(e -> text.isEmpty() || e.name().toLowerCase(Locale.GERMAN).contains(text))
                .filter(e -> blank(q.category()) || q.category().equalsIgnoreCase(e.categoryName()))
                .filter(e -> blank(q.brand()) || q.brand().equalsIgnoreCase(e.brand()))
                .filter(e -> q.priceMin() == null || e.price() >= q.priceMin())
                .filter(e -> q.priceMax() == null || e.price() <= q.priceMax())
                .toList());
        hits.sort(order(q.sort(), q.descending()));
        int size = Math.max(1, cfg().getCatalogPageSize());
        int pages = Math.max(1, (hits.size() + size - 1) / size);
        int page = Math.min(Math.max(0, q.page()), pages - 1);
        List<Entry> slice = hits.subList(Math.min(hits.size(), page * size), Math.min(hits.size(), (page + 1) * size));
        return new Page(List.copyOf(slice), hits.size(), page, size, categories, brands);
    }

    private static Comparator<Entry> order(String sort, boolean descending) {
        Comparator<Entry> byName = Comparator.comparing(e -> e.name().toLowerCase(Locale.GERMAN));
        Comparator<Entry> c = switch (sort == null ? "CATEGORY" : sort.toUpperCase()) {
            case "NAME" -> byName;
            case "PRICE" -> Comparator.comparingLong(Entry::price);
            case "POWER" -> Comparator.comparingInt(e -> e.powerMax() == null ? 0 : e.powerMax());
            case "CATEGORY" -> Comparator.comparing((Entry e) -> label(e.categoryName(), e.categoryTitle())
                    .toLowerCase(Locale.GERMAN));
            default -> throw new IllegalArgumentException("unknown sort " + sort);
        };
        c = descending ? c.reversed() : c;
        return c.thenComparing(byName).thenComparing(Entry::xmlFilename);
    }

    private static List<FilterValue> values(List<Entry> entries, java.util.function.Function<Entry, String> name,
                                            java.util.function.Function<Entry, String> title) {
        Map<String, FilterValue> byName = new TreeMap<>();
        for (Entry e : entries) {
            String n = name.apply(e);
            if (!blank(n)) {
                byName.putIfAbsent(n, new FilterValue(n, title.apply(e)));
            }
        }
        return byName.values().stream()
                .sorted(Comparator.comparing((FilterValue v) -> label(v.name(), v.title()).toLowerCase(Locale.GERMAN)))
                .toList();
    }

    private static String label(String name, String title) {
        return blank(title) ? (name == null ? "" : name) : title;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static Entry entry(BridgeDtos.CatalogItem i) {
        return new Entry(i.xmlFilename(), i.name() == null ? i.xmlFilename() : i.name(), i.brand(), i.brandTitle(),
                i.categoryName(), i.categoryTitle(), Math.round(i.price() == null ? 0 : i.price()),
                Math.round(i.dailyUpkeep() == null ? 0 : i.dailyUpkeep()), i.powerMin(), i.powerMax(),
                Boolean.TRUE.equals(i.isMod()), i.motorized());
    }

    /** RFH-K2: the machine put together, with the expected delivery from the current game time (L1-L3). */
    public Quote quote(Savegame sg, BridgeDtos.CatalogItem item, Map<String, Integer> choice) {
        MachineConfigurator.Result r = MachineConfigurator.configure(item, choice, cfg());
        DeliveryTime.Estimate d = DeliveryTime.estimate(sg.getCurrentGameTime(), r.daysMin(), r.daysMax(),
                absence.daysPerPeriod(sg), props.getFormulas().getAbsenceScaling(), cfg());
        return new Quote(r, d);
    }
}
