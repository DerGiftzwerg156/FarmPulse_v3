package de.farmpulse.rpsim.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.contract.LeaseOutService;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookNotice;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldBookService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3.3 section F: the app "Feldbuch", tab "Dokumentation" - every field with its running season and its entries
 * per harvest year, corrections, "Ernte eintragen" and closing / reopening a harvest year.
 */
@RestController
public class FieldBookController {

    /** A value as shown: {@code manual} = corrected by the player (the detection leaves it alone). */
    public record ValueView(Object value, boolean manual) {
    }

    /**
     * One entry. {@code rollingNeeded} false = the crop is not rolled (FS25 needsRolling), null = unknown; {@code held}
     * = ended in a closed year and kept back until it is reopened; {@code locked} = in a closed year.
     */
    public record EntryView(long id, String status, Integer harvestYear, Double hectares, boolean firstEntry, boolean held,
                            boolean locked, Double litersPerHectare, List<String> sprayTypes, Boolean rollingNeeded,
                            Map<String, ValueView> values) {
    }

    /** {@code state} ACTIVE, LEASED_OUT (verpachtet), LEASE_ENDED (Pacht beendet) or SOLD (verkauft). */
    public record FieldView(int farmlandId, String name, Double hectares, String state, EntryView running,
                            List<EntryView> entries) {
    }

    public record CropView(String name, String title, String fillType, List<String> products, Boolean needsRolling) {
    }

    /** Litres the game counted for an entry of a closed year (taken over when it is reopened). */
    public record NoticeView(long entryId, int farmlandId, Integer harvestYear, double liters) {
    }

    /**
     * {@code fieldsTracked} false = the mod reports no fields (older mod); {@code showLime} / {@code showWeeds} = the
     * soil rule is on in the savegame or unknown (owner decision 2026-10-09: unknown = shown).
     */
    public record FieldBookView(boolean fieldsTracked, Integer currentYear, List<Integer> closedYears, List<Integer> years,
                                boolean showLime, boolean showWeeds, boolean cropsFromMap, List<CropView> crops,
                                List<FieldView> fields, List<NoticeView> notices) {
    }

    public record ValueRequest(@NotNull FieldBookService.Value field, String value) {
    }

    private final SavegameContext context;
    private final FieldBookService book;
    private final FactsService facts;
    private final LeaseOutService leaseOut;
    private final ContractRepository contracts;

    public FieldBookController(SavegameContext context, FieldBookService book, FactsService facts,
                               LeaseOutService leaseOut, ContractRepository contracts) {
        this.context = context;
        this.book = book;
        this.facts = facts;
        this.leaseOut = leaseOut;
        this.contracts = contracts;
    }

    @GetMapping("/api/field-book")
    @Transactional(readOnly = true)
    public FieldBookView view() {
        return view(context.requireActive());
    }

    /** F4: corrects a value; {@code value} null gives it back to the detection ("automatisch"). */
    @PostMapping("/api/field-book/entries/{id}/values")
    @Transactional
    public FieldBookView setValue(@PathVariable long id, @Valid @RequestBody ValueRequest r) {
        Savegame sg = context.requireActive();
        book.setValue(sg, id, r.field(), r.value());
        return view(sg);
    }

    /** F4: "Ernte eintragen" - ends the running season of the field with the current FS25 year. */
    @PostMapping("/api/field-book/fields/{farmlandId}/harvest")
    @Transactional
    public FieldBookView harvest(@PathVariable int farmlandId) {
        Savegame sg = context.requireActive();
        book.harvestNow(sg, farmlandId);
        return view(sg);
    }

    @PostMapping("/api/field-book/years/{year}/close")
    @Transactional
    public FieldBookView close(@PathVariable int year) {
        Savegame sg = context.requireActive();
        book.closeYear(sg, year);
        return view(sg);
    }

    @PostMapping("/api/field-book/years/{year}/reopen")
    @Transactional
    public FieldBookView reopen(@PathVariable int year) {
        Savegame sg = context.requireActive();
        book.reopenYear(sg, year);
        return view(sg);
    }

    private FieldBookView view(Savegame sg) {
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        List<FieldBookService.Crop> crops = book.crops(sg);
        Map<String, FieldBookService.Crop> cropByName = new LinkedHashMap<>();
        crops.forEach(c -> cropByName.put(c.name(), c));
        List<Integer> closed = book.closedYears(sg);
        Map<Integer, BridgeDtos.Field> own = new TreeMap<>();
        if (f != null && f.fields() != null) {
            f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> own.put(x.farmlandId(), x));
        }
        Map<Integer, List<FieldBookEntry>> byField = new TreeMap<>();
        own.keySet().forEach(id -> byField.put(id, new ArrayList<>()));
        List<Integer> years = new ArrayList<>();
        for (FieldBookEntry e : book.all(sg)) {
            byField.computeIfAbsent(e.getFarmlandId(), k -> new ArrayList<>()).add(e);
            if (e.getHarvestYear() != null && !years.contains(e.getHarvestYear())) {
                years.add(e.getHarvestYear());
            }
        }
        years.sort(Comparator.reverseOrder());
        List<FieldView> fields = new ArrayList<>();
        for (var en : byField.entrySet()) {
            int id = en.getKey();
            List<FieldBookEntry> list = en.getValue();
            BridgeDtos.Field field = own.get(id);
            EntryView running = list.stream().filter(e -> e.getStatus() == FieldBookEntry.Status.RUNNING).findFirst()
                    .map(e -> entry(e, closed, cropByName)).orElse(null);
            List<EntryView> done = list.stream().filter(e -> e.getStatus() != FieldBookEntry.Status.RUNNING)
                    .sorted(Comparator.comparing(FieldBookEntry::getHarvestYear, Comparator.reverseOrder())
                            .thenComparing(FieldBookEntry::getId))
                    .map(e -> entry(e, closed, cropByName)).toList();
            String name = field != null ? field.name()
                    : list.stream().map(FieldBookEntry::getFieldName).filter(n -> n != null).reduce((a, b) -> b)
                    .orElse(String.valueOf(id));
            fields.add(new FieldView(id, name, field == null ? null : field.hectares(), state(sg, id, field != null),
                    running, done));
        }
        Map<Long, FieldBookEntry> entryById = new LinkedHashMap<>();
        book.all(sg).forEach(e -> entryById.put(e.getId(), e));
        List<NoticeView> notices = new ArrayList<>();
        for (FieldBookNotice n : book.notices(sg)) {
            FieldBookEntry e = entryById.get(n.getEntryId());
            if (e != null) {
                notices.add(new NoticeView(e.getId(), e.getFarmlandId(), e.getHarvestYear(), n.getLiters()));
            }
        }
        BridgeDtos.FieldRules rules = f == null ? null : f.fieldRules();
        boolean showLime = rules == null || !Boolean.FALSE.equals(rules.limeRequired());
        boolean showWeeds = rules == null || !Boolean.FALSE.equals(rules.weedsEnabled());
        boolean fromMap = facts.marketContext(sg).map(m -> m.fruitTypes() != null).orElse(false);
        Integer year = f == null || f.calendar() == null ? null : f.calendar().year();
        return new FieldBookView(f != null && f.fields() != null, year, closed, years, showLime, showWeeds, fromMap,
                crops.stream().map(c -> new CropView(c.name(), c.title(), c.fillType(), c.products(), c.needsRolling()))
                        .toList(), fields, notices);
    }

    private String state(Savegame sg, int farmlandId, boolean exported) {
        if (exported) {
            return "ACTIVE";
        }
        if (leaseOut.active(sg, farmlandId).isPresent()) {
            return "LEASED_OUT";
        }
        boolean leased = contracts.findBySavegameOrderByIdDesc(sg).stream()
                .anyMatch(c -> c.getKind() == ContractKind.LEASE && c.getFarmlandId() != null && c.getFarmlandId() == farmlandId);
        return leased ? "LEASE_ENDED" : "SOLD";
    }

    private static EntryView entry(FieldBookEntry e, List<Integer> closed, Map<String, FieldBookService.Crop> crops) {
        Map<String, ValueView> v = new LinkedHashMap<>();
        v.put("FRUIT_TYPE", new ValueView(e.fruitType(), e.getFruitTypeManual() != null));
        v.put("FILL_TYPE", new ValueView(e.fillType(), e.getFillTypeManual() != null));
        v.put("LITERS", new ValueView(e.liters(), e.getLitersManual() != null));
        v.put("FERT1", new ValueView(e.fert1(), e.getFert1Manual() != null));
        v.put("FERT2", new ValueView(e.fert2(), e.getFert2Manual() != null));
        v.put("LIMED", new ValueView(e.limed(), e.getLimedManual() != null));
        v.put("ROLLED", new ValueView(e.rolled(), e.getRolledManual() != null));
        v.put("WEEDS", new ValueView(e.weeds(), e.getWeedsManual() != null));
        v.put("MULCHED", new ValueView(e.mulched(), e.getMulchedManual() != null));
        FieldBookService.Crop crop = e.fruitType() == null ? null : crops.get(e.fruitType());
        Double perHa = e.liters() != null && e.getHectares() != null && e.getHectares() > 0
                ? e.liters() / e.getHectares() : null;
        return new EntryView(e.getId(), e.getStatus().name(), e.getHarvestYear(), e.getHectares(), e.isFirstEntry(),
                e.isHeld(), e.getHarvestYear() != null && closed.contains(e.getHarvestYear()), perHa,
                List.copyOf(FieldBookService.sprayTypes(e)), crop == null ? null : crop.needsRolling(), v);
    }
}
