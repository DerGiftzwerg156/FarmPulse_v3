package de.farmpulse.rpsim.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.3 section E: the evaluation of the field book (owner decisions 2026-10-08 / 2026-10-09). The last x harvest
 * years up to and including the current FS25 year (x chosen by the player and kept per savegame, default
 * {@code field-book.default-years}, 0 = all years); every finished entry counts except the ones held back in a closed
 * year. Litres per hectare = litres / area of the entry; an entry without litres has none and counts in no average.
 * Comparison per crop and harvest product over all fields: average l/ha with and without each measure, always with n;
 * lime and weeds only with their soil rule on, rolling not for crops that are not rolled.
 */
@Service
public class FieldBookEvaluationService {

    /** One entry of the table (a cell field x year). */
    public record Cell(long entryId, int harvestYear, String status, String fruitType, String fillType, Double liters,
                       Double hectares, Double litersPerHectare, Map<String, Boolean> measures) {
    }

    public record FieldRow(int farmlandId, String name, List<Cell> cells) {
    }

    /** Average l/ha with / without a measure; average null when n = 0. */
    public record MeasureComparison(String measure, Double withAvg, int withN, Double withoutAvg, int withoutN) {
    }

    public record CropComparison(String fruitType, String fillType, List<MeasureComparison> measures) {
    }

    /**
     * {@code years} = the chosen period (0 = all), {@code fromYear} / {@code toYear} = the harvest years shown,
     * {@code yearsAvailable} = harvest years from the first entry up to the current year.
     */
    public record Evaluation(int years, int defaultYears, Integer fromYear, Integer toYear, int yearsAvailable,
                             boolean showLime, boolean showWeeds, List<FieldRow> fields,
                             List<CropComparison> comparisons) {
    }

    private final FieldBookService book;
    private final FactsService facts;
    private final RpsimProperties props;

    public FieldBookEvaluationService(FieldBookService book, FactsService facts, RpsimProperties props) {
        this.book = book;
        this.facts = facts;
        this.props = props;
    }

    /** R33-E1 (owner decision 2026-10-09): keeps the period per savegame; 0 = all years. */
    @Transactional
    public void choosePeriod(Savegame sg, int years) {
        if (years < 0) {
            throw new BusinessRuleException("FIELD_BOOK_YEARS", "Der Zeitraum muss 1 oder mehr Jahre oder „alle Jahre“ sein.");
        }
        sg.setFieldBookYears(years);
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(Savegame sg) {
        int defaultYears = Math.max(1, props.getFormulas().getFieldBook().getDefaultYears());
        int years = sg.getFieldBookYears() == null ? defaultYears : sg.getFieldBookYears();
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        List<FieldBookEntry> finished = book.all(sg).stream()
                .filter(e -> e.getStatus() != FieldBookEntry.Status.RUNNING && !e.isHeld() && e.getHarvestYear() != null)
                .toList();
        Integer current = f != null && f.calendar() != null && f.calendar().year() != null ? f.calendar().year()
                : finished.stream().map(FieldBookEntry::getHarvestYear).max(Integer::compare).orElse(null);
        Integer first = finished.stream().map(FieldBookEntry::getHarvestYear).min(Integer::compare).orElse(null);
        int available = current == null || first == null ? 0 : Math.max(0, current - first + 1);
        Integer to = current;
        Integer from = current == null ? null : years == 0 ? first : current - years + 1;
        List<FieldBookEntry> inPeriod = finished.stream()
                .filter(e -> to != null && from != null && e.getHarvestYear() >= from && e.getHarvestYear() <= to)
                .toList();
        BridgeDtos.FieldRules rules = f == null ? null : f.fieldRules();
        boolean showLime = rules == null || !Boolean.FALSE.equals(rules.limeRequired());
        boolean showWeeds = rules == null || !Boolean.FALSE.equals(rules.weedsEnabled());
        Map<String, FieldBookService.Crop> crops = new LinkedHashMap<>();
        book.crops(sg).forEach(c -> crops.put(c.name(), c));
        return new Evaluation(years, defaultYears, from, to, available, showLime, showWeeds, rows(f, inPeriod),
                comparisons(inPeriod, crops, showLime, showWeeds));
    }

    private static List<FieldRow> rows(BridgeDtos.FarmFacts f, List<FieldBookEntry> entries) {
        Map<Integer, String> names = new TreeMap<>();
        if (f != null && f.fields() != null) {
            f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> names.put(x.farmlandId(), x.name()));
        }
        Map<Integer, List<Cell>> byField = new TreeMap<>();
        Map<Integer, String> lastName = new TreeMap<>();
        for (FieldBookEntry e : entries.stream().sorted(Comparator.comparing(FieldBookEntry::getHarvestYear)).toList()) {
            byField.computeIfAbsent(e.getFarmlandId(), k -> new ArrayList<>()).add(cell(e));
            if (e.getFieldName() != null) {
                lastName.put(e.getFarmlandId(), e.getFieldName());
            }
        }
        List<FieldRow> rows = new ArrayList<>();
        byField.forEach((id, cells) -> rows.add(new FieldRow(id,
                names.getOrDefault(id, lastName.getOrDefault(id, String.valueOf(id))), cells)));
        return rows;
    }

    private static Cell cell(FieldBookEntry e) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("FERT1", e.fert1());
        m.put("FERT2", e.fert2());
        m.put("LIMED", e.limed());
        m.put("ROLLED", e.rolled());
        m.put("WEEDS", e.weeds());
        m.put("MULCHED", e.mulched());
        return new Cell(e.getId(), e.getHarvestYear(), e.getStatus().name(), e.fruitType(), e.fillType(), e.liters(),
                e.getHectares(), perHectare(e), m);
    }

    /** Litres / area of the entry; null without litres or area (owner decision 2026-10-08: empty does not count). */
    public static Double perHectare(FieldBookEntry e) {
        return e.liters() != null && e.getHectares() != null && e.getHectares() > 0 ? e.liters() / e.getHectares() : null;
    }

    private static List<CropComparison> comparisons(List<FieldBookEntry> entries, Map<String, FieldBookService.Crop> crops,
                                                    boolean showLime, boolean showWeeds) {
        Map<List<String>, List<FieldBookEntry>> groups = new TreeMap<>(Comparator
                .comparing((List<String> k) -> k.get(0)).thenComparing(k -> k.get(1) == null ? "" : k.get(1)));
        for (FieldBookEntry e : entries) {
            if (e.fruitType() != null && perHectare(e) != null) {
                groups.computeIfAbsent(java.util.Arrays.asList(e.fruitType(), e.fillType()), k -> new ArrayList<>()).add(e);
            }
        }
        List<CropComparison> result = new ArrayList<>();
        groups.forEach((key, list) -> {
            FieldBookService.Crop crop = crops.get(key.get(0));
            List<MeasureComparison> measures = new ArrayList<>();
            measures.add(compare("FERT1", list, FieldBookEntry::fert1));
            measures.add(compare("FERT2", list, FieldBookEntry::fert2));
            if (showLime) {
                measures.add(compare("LIMED", list, FieldBookEntry::limed));
            }
            if (crop == null || !Boolean.FALSE.equals(crop.needsRolling())) {
                measures.add(compare("ROLLED", list, FieldBookEntry::rolled)); // not for crops that are not rolled
            }
            if (showWeeds) {
                measures.add(compare("WEEDS", list, FieldBookEntry::weeds));
            }
            measures.add(compare("MULCHED", list, FieldBookEntry::mulched));
            result.add(new CropComparison(key.get(0), key.get(1), measures));
        });
        return result;
    }

    private static MeasureComparison compare(String measure, List<FieldBookEntry> list, Predicate<FieldBookEntry> done) {
        List<Double> with = list.stream().filter(done).map(FieldBookEvaluationService::perHectare).filter(Objects::nonNull)
                .toList();
        List<Double> without = list.stream().filter(done.negate()).map(FieldBookEvaluationService::perHectare)
                .filter(Objects::nonNull).toList();
        return new MeasureComparison(measure, avg(with), with.size(), avg(without), without.size());
    }

    private static Double avg(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
