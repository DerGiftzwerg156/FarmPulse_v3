package de.farmpulse.rpsim.dealer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.config.RpsimProperties;

/**
 * Roadmap vehicle dealer RFH-K2 / L1 / L2 / L3: one machine put together like in the shop of the game. Pure
 * calculation on a catalog entry of store_catalog.json:
 * <ul>
 * <li>the configuration: the game's default per type ({@code defaultConfigurations}, otherwise
 * ConfigurationUtil.getDefaultConfigIdFromItems: first default and selectable option, else the first selectable), the
 * player's choice on top, then the options each chosen option sets with it ({@code dependent},
 * VehicleLoadingData:getConfigurations);</li>
 * <li>list price and daily upkeep: base value plus the value of every option of the configuration
 * (StoreItemUtil.getCosts with "price" / "dailyUpkeep");</li>
 * <li>the package of the manufacturer the configuration matches (ConfigurationUtil.getConfigurationsMatchConfigSets),
 * null = "individuell";</li>
 * <li>the delivery time span in days of the reference month: the span of the category group (L1) plus the extra days of
 * every type whose option deviates from the standard (L2). The standard is the game's default or a package of the
 * manufacturer (owner decision 2026-10-15) - the one with the fewest extra days counts. Options set by another option
 * are not chosen and add nothing.</li>
 * </ul>
 */
public final class MachineConfigurator {

    /** A type and option of the configuration (for "legt auch fest" and the deviations of a cart position). */
    public record TypeOption(String type, String title, int index, String option) {
    }

    /** The put-together machine. {@code extraDays} = L2 surcharge in days of the reference month. */
    public record Result(Map<String, Integer> configurations, long listPrice, long dailyUpkeep, String configurationSet,
                         List<TypeOption> setWith, List<TypeOption> deviations, double daysMin, double daysMax,
                         double extraDays) {
    }

    private MachineConfigurator() {
    }

    /** The default option of every type of the item. */
    public static Map<String, Integer> defaults(BridgeDtos.CatalogItem item) {
        Map<String, Integer> given = item.defaultConfigurations() == null ? Map.of() : item.defaultConfigurations();
        Map<String, Integer> out = new TreeMap<>();
        for (BridgeDtos.CatalogConfiguration c : types(item)) {
            Integer index = given.get(c.name());
            out.put(c.name(), index != null && option(c, index) != null ? index : gameDefault(c));
        }
        return out;
    }

    /** ConfigurationUtil.getDefaultConfigIdFromItems. */
    static int gameDefault(BridgeDtos.CatalogConfiguration c) {
        for (BridgeDtos.CatalogOption o : options(c)) {
            if (Boolean.TRUE.equals(o.isDefault()) && !Boolean.FALSE.equals(o.isSelectable())) {
                return o.index();
            }
        }
        for (BridgeDtos.CatalogOption o : options(c)) {
            if (!Boolean.FALSE.equals(o.isSelectable())) {
                return o.index();
            }
        }
        return 1;
    }

    /**
     * Puts the machine together. {@code choice} = type name -> index of the player's choice; unknown types, unknown
     * options and options that are not selectable are refused.
     */
    public static Result configure(BridgeDtos.CatalogItem item, Map<String, Integer> choice,
                                   RpsimProperties.MachineDealer cfg) {
        Map<String, Integer> defaults = defaults(item);
        Map<String, Integer> config = new TreeMap<>(defaults);
        for (Map.Entry<String, Integer> e : (choice == null ? Map.<String, Integer>of() : choice).entrySet()) {
            BridgeDtos.CatalogConfiguration type = type(item, e.getKey());
            if (type == null) {
                throw new IllegalArgumentException("unknown configuration type " + e.getKey());
            }
            BridgeDtos.CatalogOption o = e.getValue() == null ? null : option(type, e.getValue());
            if (o == null || Boolean.FALSE.equals(o.isSelectable())) {
                throw new IllegalArgumentException("option " + e.getValue() + " of " + e.getKey() + " cannot be chosen");
            }
            config.put(e.getKey(), e.getValue());
        }
        // options set together with a chosen one
        Map<String, Integer> forced = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(config).entrySet()) {
            BridgeDtos.CatalogOption o = option(type(item, e.getKey()), e.getValue());
            for (BridgeDtos.CatalogDependent d : o == null || o.dependent() == null ? List.<BridgeDtos.CatalogDependent>of()
                    : o.dependent()) {
                BridgeDtos.CatalogConfiguration t = type(item, d.name());
                if (t != null && d.index() != null && option(t, d.index()) != null) {
                    forced.put(d.name(), d.index());
                }
            }
        }
        config.putAll(forced);
        double price = item.price() == null ? 0 : item.price();
        double upkeep = item.dailyUpkeep() == null ? 0 : item.dailyUpkeep();
        for (Map.Entry<String, Integer> e : config.entrySet()) {
            BridgeDtos.CatalogOption o = option(type(item, e.getKey()), e.getValue());
            price += o == null || o.price() == null ? 0 : o.price();
            upkeep += o == null || o.dailyUpkeep() == null ? 0 : o.dailyUpkeep();
        }
        List<TypeOption> setWith = new ArrayList<>();
        forced.forEach((name, index) -> setWith.add(typeOption(item, name, index)));
        List<TypeOption> deviations = new ArrayList<>();
        config.forEach((name, index) -> {
            if (!forced.containsKey(name) && !index.equals(defaults.get(name))) {
                deviations.add(typeOption(item, name, index));
            }
        });
        double extra = extraDays(item, config, forced.keySet(), defaults, cfg);
        double[] span = span(item.categoryName(), cfg);
        // discount options (negative price) never take the machine below nothing
        return new Result(config, Math.max(0, Math.round(price)), Math.max(0, Math.round(upkeep)),
                matchingSet(item, config), setWith, deviations,
                span[0] + extra, span[1] + extra, extra);
    }

    /** L1: span of the category group, else the default span. */
    static double[] span(String categoryName, RpsimProperties.MachineDealer cfg) {
        String category = categoryName == null ? "" : categoryName.toUpperCase();
        for (RpsimProperties.DeliveryGroup g : cfg.getDeliveryGroups().values()) {
            if (g.getCategories() != null && g.getCategories().stream().anyMatch(c -> c.equalsIgnoreCase(category))) {
                return new double[] {g.getDaysMin(), g.getDaysMax()};
            }
        }
        return new double[] {cfg.getDefaultDeliveryDaysMin(), cfg.getDefaultDeliveryDaysMax()};
    }

    /** L2: the fewest extra days over the standards (game default, every package on top of it). */
    static double extraDays(BridgeDtos.CatalogItem item, Map<String, Integer> config, Set<String> forced,
                            Map<String, Integer> defaults, RpsimProperties.MachineDealer cfg) {
        List<Map<String, Integer>> standards = new ArrayList<>();
        standards.add(defaults);
        for (BridgeDtos.CatalogConfigurationSet set : sets(item)) {
            Map<String, Integer> s = new TreeMap<>(defaults);
            if (set.configurations() != null) {
                s.putAll(set.configurations());
            }
            standards.add(s);
        }
        double best = Double.MAX_VALUE;
        for (Map<String, Integer> standard : standards) {
            double days = 0;
            for (Map.Entry<String, Integer> e : config.entrySet()) {
                if (!forced.contains(e.getKey()) && !e.getValue().equals(standard.get(e.getKey()))) {
                    days += cfg.getOptionExtraDays().getOrDefault(e.getKey(), cfg.getOtherOptionExtraDays());
                }
            }
            best = Math.min(best, days);
        }
        return best;
    }

    /** ConfigurationUtil.getConfigurationsMatchConfigSets: the first package all of whose options are chosen. */
    static String matchingSet(BridgeDtos.CatalogItem item, Map<String, Integer> config) {
        for (BridgeDtos.CatalogConfigurationSet set : sets(item)) {
            Map<String, Integer> options = set.configurations() == null ? Map.of() : set.configurations();
            if (options.entrySet().stream().allMatch(e -> e.getValue().equals(config.get(e.getKey())))) {
                return set.name();
            }
        }
        return null;
    }

    /** Type names of the item, for the check of a stored choice. */
    public static Set<String> typeNames(BridgeDtos.CatalogItem item) {
        Set<String> names = new LinkedHashSet<>();
        types(item).forEach(c -> names.add(c.name()));
        return names;
    }

    private static TypeOption typeOption(BridgeDtos.CatalogItem item, String name, int index) {
        BridgeDtos.CatalogConfiguration t = type(item, name);
        BridgeDtos.CatalogOption o = option(t, index);
        return new TypeOption(name, t == null ? null : t.title(), index, o == null ? String.valueOf(index) : o.name());
    }

    private static List<BridgeDtos.CatalogConfiguration> types(BridgeDtos.CatalogItem item) {
        return item.configurations() == null ? List.of() : item.configurations();
    }

    private static List<BridgeDtos.CatalogOption> options(BridgeDtos.CatalogConfiguration c) {
        return c == null || c.options() == null ? List.of() : c.options();
    }

    private static List<BridgeDtos.CatalogConfigurationSet> sets(BridgeDtos.CatalogItem item) {
        return item.configurationSets() == null ? List.of() : item.configurationSets();
    }

    static BridgeDtos.CatalogConfiguration type(BridgeDtos.CatalogItem item, String name) {
        return types(item).stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    static BridgeDtos.CatalogOption option(BridgeDtos.CatalogConfiguration c, int index) {
        return options(c).stream().filter(o -> o.index() != null && o.index() == index).findFirst().orElse(null);
    }
}
