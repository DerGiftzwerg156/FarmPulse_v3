package de.farmpulse.rpsim.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.domain.FieldBookCounter;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.FieldBookNotice;
import de.farmpulse.rpsim.domain.FieldBookYear;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.farmwork.ContractorWorkService;
import de.farmpulse.rpsim.repository.FieldBookCounterRepository;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.repository.FieldBookNoticeRepository;
import de.farmpulse.rpsim.repository.FieldBookYearRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.3 section F: the field book. Keeps one running season ("laufende Saison") per own field and ends it with
 * the harvest (owner decisions 2026-10-08, open points decided 2026-10-09; {@code docs/architecture/ROADMAP_V3.3.md}):
 * <ul>
 *   <li>F1 seasons: a harvest ends the entry with the FS25 year of that moment (harvest year), everything after it
 *   belongs to the next one; a crop that is replaced without a harvest ends it as NO_HARVEST; one entry per field and
 *   harvest year (cuts of the same crop are added, a second other crop is dropped, a harvest beats "ohne Ernte");</li>
 *   <li>F2 measures from the field levels of the exports; F3 litres from the harvest counter of the mod and the
 *   contractor harvest;</li>
 *   <li>F4 corrections (manual wins, can be given back); F5 closed years (locked, late detections kept as notices).</li>
 * </ul>
 */
@Service
public class FieldBookService {

    /** Values the player can correct (and give back to the detection). */
    public enum Value { FRUIT_TYPE, FILL_TYPE, LITERS, FERT1, FERT2, LIMED, ROLLED, WEEDS, MULCHED }

    /** One crop for the dropdown; {@code needsRolling} null = unknown (older mod). */
    public record Crop(String name, String title, String fillType, List<String> products, Boolean regrows,
                       Boolean needsRolling) {
    }

    /** Kinds of fertiliser that are no fertilisation (FS25 FieldSprayType). */
    private static final Set<String> NO_FERTILISER = Set.of("NONE", "LIME");

    private final SavegameRepository savegames;
    private final FieldBookEntryRepository entries;
    private final FieldBookYearRepository years;
    private final FieldBookNoticeRepository notices;
    private final FieldBookCounterRepository counters;
    private final FactsService facts;
    private final OutboxInstructionRepository outbox;
    private final ServiceCaseRepository cases;
    private final de.farmpulse.rpsim.config.RpsimProperties props;

    public FieldBookService(SavegameRepository savegames, FieldBookEntryRepository entries, FieldBookYearRepository years,
                            FieldBookNoticeRepository notices, FieldBookCounterRepository counters, FactsService facts,
                            OutboxInstructionRepository outbox, ServiceCaseRepository cases,
                            de.farmpulse.rpsim.config.RpsimProperties props) {
        this.savegames = savegames;
        this.entries = entries;
        this.years = years;
        this.notices = notices;
        this.counters = counters;
        this.facts = facts;
        this.outbox = outbox;
        this.cases = cases;
        this.props = props;
    }

    // ------------------------------------------------------------------------------------------ crops

    /**
     * The crops of the map (market_context.fruitTypes, R33-Q1). Older mod without the block (owner decision
     * 2026-10-09): the crops the field book saw on the own fields plus the sowing list of the contractor.
     */
    @Transactional(readOnly = true)
    public List<Crop> crops(Savegame sg) {
        var ctx = facts.marketContext(sg).orElse(null);
        if (ctx != null && ctx.fruitTypes() != null) {
            return ctx.fruitTypes().stream().filter(t -> t != null && t.name() != null)
                    .map(t -> new Crop(t.name(), t.title(), t.fillType(), t.products() == null ? List.of() : t.products(),
                            t.regrows(), t.needsRolling())).toList();
        }
        Set<String> names = new LinkedHashSet<>(props.getFormulas().getContractorWork().getSowFruitTypes());
        for (FieldBookEntry e : entries.findBySavegameOrderByIdAsc(sg)) {
            if (e.fruitType() != null) {
                names.add(e.fruitType());
            }
        }
        return names.stream().sorted().map(n -> new Crop(n, null, null, List.of(), null, null)).toList();
    }

    private Map<String, Crop> cropMap(Savegame sg) {
        Map<String, Crop> m = new HashMap<>();
        for (Crop c : crops(sg)) {
            m.put(c.name(), c);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------ F1 / F2 exports

    @EventListener
    @Order(6)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null || f.calendar() == null || f.calendar().year() == null) {
            return;
        }
        update(sg, f, e.gameTime());
    }

    /**
     * One export: 1. a running season for every own field that has none yet; 2. the harvest counter (the litres of a cut
     * belong to the season the same export ends - a crop that grows again has its next season with the same crop at
     * once); 3. the change of every other field against the last export.
     */
    @Transactional
    public void update(Savegame sg, FarmFacts f, long now) {
        int year = f.calendar().year();
        Map<String, Crop> crops = cropMap(sg);
        Map<Integer, FieldBookEntry> running = new HashMap<>();
        for (FieldBookEntry r : entries.findBySavegameAndStatus(sg, Status.RUNNING)) {
            running.put(r.getFarmlandId(), r);
        }
        Map<Integer, BridgeDtos.Field> started = new HashMap<>();
        for (BridgeDtos.Field field : f.fields()) {
            if (field != null && field.farmlandId() != null && !running.containsKey(field.farmlandId())
                    && !started.containsKey(field.farmlandId())) {
                start(sg, field, now, !entries.existsBySavegameAndFarmlandId(sg, field.farmlandId()), crops);
                started.put(field.farmlandId(), field);
            }
        }
        if (f.harvests() != null) {
            counters(sg, f.harvests(), year, now);
        }
        for (BridgeDtos.Field field : f.fields()) {
            if (field == null || field.farmlandId() == null || started.containsKey(field.farmlandId())) {
                continue;
            }
            FieldBookEntry r = running.remove(field.farmlandId());
            if (r != null) {
                sample(sg, r, field, now, year, crops);
            }
        }
        // sold, leased out or lease ended: the running season is dropped, the finished years stay (2026-10-09)
        entries.deleteAll(running.values());
    }

    /** A new running season with the field state as it is now. */
    private FieldBookEntry start(Savegame sg, BridgeDtos.Field field, long now, boolean first, Map<String, Crop> crops) {
        FieldBookEntry e = new FieldBookEntry();
        e.setSavegame(sg);
        e.setFarmlandId(field.farmlandId());
        e.setFieldName(field.name());
        e.setStatus(Status.RUNNING);
        e.setStartedGameTime(now);
        e.setFirstEntry(first);
        FieldPhase phase = FieldService.phase(field);
        if (phase.standing()) {
            e.setFruitTypeAuto(field.fruitType());
            e.setFillTypeAuto(field.fillType());
        }
        if (first) {
            // owner decision 2026-10-09: in the first entry of a field the levels found at its start count as done
            int spray = nz(field.sprayLevel());
            e.setFertCountAuto(Math.min(2, spray));
            if (spray > 0) {
                addSprayType(e, field.sprayType());
            }
            e.setLimedAuto(nz(field.limeLevel()) > 0);
            Crop crop = phase.standing() ? crops.get(field.fruitType()) : null;
            e.setRolledAuto(crop != null && Boolean.TRUE.equals(crop.needsRolling())
                    && Integer.valueOf(0).equals(field.rollerLevel()));
            e.setMulchedAuto(Integer.valueOf(1).equals(field.stubbleShredLevel()));
        }
        remember(e, field, phase);
        return entries.save(e);
    }

    /** Measures and the end of a running season from the change against the last export. */
    private void sample(Savegame sg, FieldBookEntry e, BridgeDtos.Field field, long now, int year,
                        Map<String, Crop> crops) {
        FieldPhase phase = FieldService.phase(field);
        FieldPhase last = e.getLastPhase() == null ? null : FieldPhase.valueOf(e.getLastPhase());
        e.setFieldName(field.name());
        // F2 measures
        int spray = nz(field.sprayLevel());
        if (e.getLastSprayLevel() != null && spray > e.getLastSprayLevel()) {
            e.setFertCountAuto(Math.min(2, e.getFertCountAuto() + spray - e.getLastSprayLevel()));
            addSprayType(e, field.sprayType());
        }
        if (e.getLastLimeLevel() != null && nz(field.limeLevel()) > e.getLastLimeLevel()) {
            e.setLimedAuto(true);
        }
        if (Integer.valueOf(1).equals(e.getLastRollerLevel()) && Integer.valueOf(0).equals(field.rollerLevel())) {
            e.setRolledAuto(true);
        }
        if (e.getLastWeedState() != null && field.weedState() != null && field.weedState() < e.getLastWeedState()) {
            e.setWeedsAuto(true);
        }
        if (!Integer.valueOf(1).equals(e.getLastStubbleLevel()) && Integer.valueOf(1).equals(field.stubbleShredLevel())) {
            e.setMulchedAuto(true);
        }
        // F1 end by harvest: ripe at the last export, now harvested, gone or (a crop that grows again) cut back
        Crop crop = e.getFruitTypeAuto() == null ? null : crops.get(e.getFruitTypeAuto());
        boolean regrowCut = crop != null && Boolean.TRUE.equals(crop.regrows()) && phase == FieldPhase.GROWING
                && Objects.equals(field.fruitType(), e.getFruitTypeAuto());
        if (last == FieldPhase.HARVESTABLE && e.getFruitTypeAuto() != null
                && (phase == FieldPhase.HARVESTED || phase == FieldPhase.EMPTY || regrowCut)) {
            remember(e, field, phase);
            end(sg, e, Status.HARVESTED, year, now, field.hectares());
            start(sg, field, now, false, crops);
            return;
        }
        if (phase.standing()) {
            if (e.getFruitTypeAuto() == null) {
                e.setFruitTypeAuto(field.fruitType());
                if (e.getFillTypeAuto() == null) {
                    e.setFillTypeAuto(field.fillType());
                }
            } else if (!e.getFruitTypeAuto().equals(field.fruitType())) {
                // F1 end without a harvest: another crop stands on the field
                remember(e, field, phase);
                end(sg, e, Status.NO_HARVEST, year, now, field.hectares());
                start(sg, field, now, false, crops);
                return;
            }
        }
        remember(e, field, phase);
        entries.save(e);
    }

    private static void remember(FieldBookEntry e, BridgeDtos.Field field, FieldPhase phase) {
        e.setLastPhase(phase.name());
        e.setLastGrowthState(field.growthState());
        e.setLastSprayLevel(nz(field.sprayLevel()));
        e.setLastLimeLevel(nz(field.limeLevel()));
        e.setLastRollerLevel(field.rollerLevel());
        e.setLastWeedState(field.weedState());
        e.setLastStubbleLevel(field.stubbleShredLevel());
    }

    private static void addSprayType(FieldBookEntry e, String sprayType) {
        if (sprayType == null || sprayType.isBlank() || NO_FERTILISER.contains(sprayType)) {
            return;
        }
        Set<String> kinds = sprayTypes(e);
        kinds.add(sprayType);
        e.setSprayTypes(String.join(",", kinds));
    }

    public static Set<String> sprayTypes(FieldBookEntry e) {
        Set<String> kinds = new LinkedHashSet<>();
        if (e.getSprayTypes() != null && !e.getSprayTypes().isBlank()) {
            kinds.addAll(List.of(e.getSprayTypes().split(",")));
        }
        return kinds;
    }

    /** Ends a season with its harvest year and area and puts it into the year (or holds it back in a closed year). */
    private void end(Savegame sg, FieldBookEntry e, Status status, int year, long now, Double hectares) {
        e.setStatus(status);
        e.setHarvestYear(year);
        e.setEndedGameTime(now);
        e.setHectares(hectares);
        if (closed(sg, year)) {
            e.setHeld(true); // R33-F5: a closed year takes nothing; shown as a notice until it is reopened
            entries.save(e);
            return;
        }
        entries.save(e);
        resolve(sg, e);
    }

    /**
     * One entry per field and harvest year (owner decisions 2026-10-08 / 2026-10-09): a harvest of the same crop is
     * added to the harvest of the year (cuts), a harvest of another crop is dropped (only the main crop), a harvest
     * replaces an entry without harvest; an entry without harvest gives way to any entry of the year.
     */
    private void resolve(Savegame sg, FieldBookEntry e) {
        List<FieldBookEntry> others = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, e.getFarmlandId()).stream()
                .filter(o -> !o.getId().equals(e.getId()) && o.getStatus() != Status.RUNNING && !o.isHeld()
                        && Objects.equals(o.getHarvestYear(), e.getHarvestYear()))
                .toList();
        if (others.isEmpty()) {
            return;
        }
        if (e.getStatus() == Status.NO_HARVEST) {
            entries.delete(e);
            return;
        }
        Optional<FieldBookEntry> harvested = others.stream().filter(o -> o.getStatus() == Status.HARVESTED).findFirst();
        if (harvested.isPresent()) {
            FieldBookEntry target = harvested.get();
            if (Objects.equals(target.getFruitTypeAuto(), e.getFruitTypeAuto())) {
                merge(target, e);
                entries.save(target);
            }
            entries.delete(e);
            return;
        }
        entries.deleteAll(others); // only entries without harvest: the harvest wins
    }

    /** Cuts of the same crop in one harvest year: litres added, measures combined with "or". */
    private static void merge(FieldBookEntry target, FieldBookEntry src) {
        if (src.getLitersAuto() != null) {
            target.setLitersAuto((target.getLitersAuto() == null ? 0 : target.getLitersAuto()) + src.getLitersAuto());
        }
        if (src.getLitersManual() != null) {
            target.setLitersManual((target.getLitersManual() != null ? target.getLitersManual()
                    : target.getLitersAuto() == null ? 0 : target.getLitersAuto() - nzd(src.getLitersAuto()))
                    + src.getLitersManual());
        }
        target.setFertCountAuto(Math.max(target.getFertCountAuto(), src.getFertCountAuto()));
        target.setLimedAuto(target.isLimedAuto() || src.isLimedAuto());
        target.setRolledAuto(target.isRolledAuto() || src.isRolledAuto());
        target.setWeedsAuto(target.isWeedsAuto() || src.isWeedsAuto());
        target.setMulchedAuto(target.isMulchedAuto() || src.isMulchedAuto());
        if (target.getFert1Manual() == null) target.setFert1Manual(src.getFert1Manual());
        if (target.getFert2Manual() == null) target.setFert2Manual(src.getFert2Manual());
        if (target.getLimedManual() == null) target.setLimedManual(src.getLimedManual());
        if (target.getRolledManual() == null) target.setRolledManual(src.getRolledManual());
        if (target.getWeedsManual() == null) target.setWeedsManual(src.getWeedsManual());
        if (target.getMulchedManual() == null) target.setMulchedManual(src.getMulchedManual());
        for (String kind : sprayTypes(src)) {
            addSprayType(target, kind);
        }
        if (target.getFillTypeAuto() == null) {
            target.setFillTypeAuto(src.getFillTypeAuto());
        }
        target.setHectares(src.getHectares());
        target.setEndedGameTime(src.getEndedGameTime());
    }

    // ------------------------------------------------------------------------------------------ F3 litres

    /** The difference of every harvest counter since the last export is booked (a falling counter is taken back). */
    private void counters(Savegame sg, List<BridgeDtos.HarvestCounter> harvests, int year, long now) {
        Map<String, FieldBookCounter> known = new HashMap<>();
        for (FieldBookCounter c : counters.findBySavegame(sg)) {
            known.put(key(c.getFarmlandId(), c.getFruitType(), c.getFillType()), c);
        }
        for (BridgeDtos.HarvestCounter h : harvests) {
            if (h == null || h.farmlandId() == null || h.fruitType() == null || h.fillType() == null || h.liters() == null) {
                continue;
            }
            FieldBookCounter c = known.get(key(h.farmlandId(), h.fruitType(), h.fillType()));
            if (c == null) {
                c = new FieldBookCounter();
                c.setSavegame(sg);
                c.setFarmlandId(h.farmlandId());
                c.setFruitType(h.fruitType());
                c.setFillType(h.fillType());
                c.setLastLiters(0);
            }
            double delta = h.liters() - c.getLastLiters();
            c.setLastLiters(h.liters());
            counters.save(c);
            if (delta != 0) {
                book(sg, h.farmlandId(), h.fruitType(), h.fillType(), delta, year, now);
            }
        }
    }

    private static String key(int farmlandId, String fruitType, String fillType) {
        return farmlandId + "|" + fruitType + "|" + fillType;
    }

    /**
     * Owner decision 2026-10-09 (R33-F3): 1. the running season with this crop; 2. else the harvest of the same crop in
     * the current FS25 year (late litres, half harvested fields, further cuts); 3. else the running season, which takes
     * the crop over. An entry of a closed year takes the litres as a notice.
     */
    private void book(Savegame sg, int farmlandId, String fruitType, String fillType, double delta, int year, long now) {
        List<FieldBookEntry> ofField = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, farmlandId);
        Optional<FieldBookEntry> running = ofField.stream().filter(x -> x.getStatus() == Status.RUNNING).findFirst();
        FieldBookEntry target = running.filter(x -> fruitType.equals(x.getFruitTypeAuto())).orElse(null);
        if (target == null) {
            target = ofField.stream().filter(x -> x.getStatus() == Status.HARVESTED && !x.isHeld()
                            && Integer.valueOf(year).equals(x.getHarvestYear()) && fruitType.equals(x.getFruitTypeAuto()))
                    .max(Comparator.comparing(FieldBookEntry::getId)).orElse(null);
        }
        if (target == null && running.isPresent()) {
            target = running.get();
            if (target.getFruitTypeAuto() == null) {
                target.setFruitTypeAuto(fruitType);
            }
        }
        if (target == null) {
            return; // field no longer own: nothing to book
        }
        if (target.getHarvestYear() != null && closed(sg, target.getHarvestYear())) {
            FieldBookNotice n = new FieldBookNotice();
            n.setSavegame(sg);
            n.setEntryId(target.getId());
            n.setLiters(delta);
            n.setCreatedGameTime(now);
            notices.save(n);
            return;
        }
        addLiters(target, delta, fillType);
        entries.save(target);
    }

    private static void addLiters(FieldBookEntry e, double delta, String fillType) {
        e.setLitersAuto(Math.max(0, nzd(e.getLitersAuto()) + delta));
        if (delta > 0 && fillType != null) {
            e.setFillTypeAuto(fillType); // the product the machines actually harvested
        }
    }

    /**
     * R33-F3: a contractor harvest (R31-A1) is a state jump of the field, no combine; its litres come with the
     * STORAGE_TRANSFER of the batch: into the running season of the field when it has a crop, else into its harvest of
     * the current FS25 year. A LIME work ticks "gekalkt" of the running season (fertilising raises the spray level the
     * next export sees, so it is not counted here a second time).
     */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !ContractorWorkService.RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        OutboxInstruction o = outbox.findByInstructionId(e.instructionId()).orElse(null);
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (o == null || sc == null || sc.getFarmlandId() == null) {
            return;
        }
        Savegame sg = sc.getSavegame();
        int farmlandId = sc.getFarmlandId();
        List<FieldBookEntry> ofField = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, farmlandId);
        Optional<FieldBookEntry> running = ofField.stream().filter(x -> x.getStatus() == Status.RUNNING).findFirst();
        if (o.getType() == InstructionType.FIELD_WORK && "LIME".equals(sc.getReference())) {
            running.ifPresent(r -> {
                r.setLimedAuto(true);
                entries.save(r);
            });
            return;
        }
        if (o.getType() != InstructionType.STORAGE_TRANSFER || !"HARVEST".equals(sc.getReference())
                || sc.getQuantity() == null) {
            return;
        }
        Integer year = facts.latest(sg).map(FarmFacts::calendar).map(BridgeDtos.Calendar::year).orElse(null);
        FieldBookEntry target = running.filter(r -> r.getFruitTypeAuto() != null).orElseGet(() -> ofField.stream()
                .filter(x -> x.getStatus() == Status.HARVESTED && !x.isHeld() && Objects.equals(x.getHarvestYear(), year))
                .max(Comparator.comparing(FieldBookEntry::getId)).orElse(null));
        if (target == null) {
            return;
        }
        if (target.getHarvestYear() != null && closed(sg, target.getHarvestYear())) {
            FieldBookNotice n = new FieldBookNotice();
            n.setSavegame(sg);
            n.setEntryId(target.getId());
            n.setLiters(sc.getQuantity());
            n.setCreatedGameTime(sg.getCurrentGameTime());
            notices.save(n);
            return;
        }
        addLiters(target, sc.getQuantity(), sc.getTitle());
        entries.save(target);
    }

    // ------------------------------------------------------------------------------------------ rewind

    /**
     * Owner decision 2026-10-09: an older savegame was loaded - entries that ended after the loaded game time are running
     * again, the seasons begun after it are dropped (corrections stay; closed years stay locked). The litres follow with
     * the falling harvest counter.
     */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long to = e.rewoundToGameTime();
        Map<Integer, List<FieldBookEntry>> byField = new HashMap<>();
        for (FieldBookEntry x : entries.findBySavegameOrderByIdAsc(sg)) {
            byField.computeIfAbsent(x.getFarmlandId(), k -> new ArrayList<>()).add(x);
        }
        for (List<FieldBookEntry> list : byField.values()) {
            // seasons begun after the loaded time (entries of a closed year stay locked)
            List<FieldBookEntry> later = list.stream().filter(x -> x.getStartedGameTime() > to
                    && (x.getHarvestYear() == null || x.isHeld() || !closed(sg, x.getHarvestYear()))).toList();
            FieldBookEntry reopen = list.stream().filter(x -> x.getStartedGameTime() <= to && x.getEndedGameTime() != null
                            && x.getEndedGameTime() > to && (x.isHeld() || !closed(sg, x.getHarvestYear())))
                    .max(Comparator.comparing(FieldBookEntry::getId)).orElse(null);
            if (reopen == null) {
                continue; // nothing of this field ended after the loaded time
            }
            entries.deleteAll(later);
            reopen.setStatus(Status.RUNNING);
            reopen.setHarvestYear(null);
            reopen.setEndedGameTime(null);
            reopen.setHectares(null);
            reopen.setHeld(false);
            entries.save(reopen);
        }
        notices.deleteAll(notices.findBySavegameOrderByIdAsc(sg).stream().filter(n -> n.getCreatedGameTime() > to).toList());
    }

    // ------------------------------------------------------------------------------------------ F4 / F5 player

    /** F4: corrects one value of an entry; {@code value} null gives it back to the detection. */
    @Transactional
    public FieldBookEntry setValue(Savegame sg, long entryId, Value which, String value) {
        FieldBookEntry e = own(sg, entryId);
        requireOpen(sg, e);
        switch (which) {
            case FRUIT_TYPE -> e.setFruitTypeManual(text(value));
            case FILL_TYPE -> e.setFillTypeManual(text(value));
            case LITERS -> e.setLitersManual(liters(value));
            case FERT1 -> e.setFert1Manual(bool(value));
            case FERT2 -> e.setFert2Manual(bool(value));
            case LIMED -> e.setLimedManual(bool(value));
            case ROLLED -> e.setRolledManual(bool(value));
            case WEEDS -> e.setWeedsManual(bool(value));
            case MULCHED -> e.setMulchedManual(bool(value));
            default -> throw new IllegalStateException();
        }
        return entries.save(e);
    }

    /** F4 fallback (owner decision 2026-10-08): "Ernte eintragen" ends the running season with the current FS25 year. */
    @Transactional
    public FieldBookEntry harvestNow(Savegame sg, int farmlandId) {
        FarmFacts f = facts.latest(sg).orElse(null);
        FieldBookEntry r = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, farmlandId).stream()
                .filter(x -> x.getStatus() == Status.RUNNING).findFirst()
                .orElseThrow(() -> new NotFoundException("Keine laufende Saison auf Feld " + farmlandId));
        if (f == null || f.calendar() == null || f.calendar().year() == null) {
            throw new BusinessRuleException("FIELD_BOOK_NO_YEAR", "Das FS25-Jahr ist noch nicht bekannt.");
        }
        BridgeDtos.Field field = f.fields() == null ? null : f.fields().stream()
                .filter(x -> x != null && Integer.valueOf(farmlandId).equals(x.farmlandId())).findFirst().orElse(null);
        long now = sg.getCurrentGameTime();
        end(sg, r, Status.HARVESTED, f.calendar().year(), now, field == null ? null : field.hectares());
        if (field != null) {
            start(sg, field, now, false, cropMap(sg));
        }
        return r;
    }

    /** F5: closes a harvest year - its entries are locked, also for the automatic capture. */
    @Transactional
    public void closeYear(Savegame sg, int year) {
        if (closed(sg, year)) {
            return;
        }
        FieldBookYear y = new FieldBookYear();
        y.setSavegame(sg);
        y.setHarvestYear(year);
        y.setClosedGameTime(sg.getCurrentGameTime());
        years.save(y);
    }

    /** F5: reopens a harvest year and takes over what the game detected in the meantime. */
    @Transactional
    public void reopenYear(Savegame sg, int year) {
        years.findBySavegameAndHarvestYear(sg, year).ifPresent(years::delete);
        years.flush();
        for (FieldBookNotice n : notices.findBySavegameOrderByIdAsc(sg)) {
            FieldBookEntry e = entries.findById(n.getEntryId()).orElse(null);
            if (e == null) {
                notices.delete(n);
            } else if (Integer.valueOf(year).equals(e.getHarvestYear())) {
                addLiters(e, n.getLiters(), null);
                entries.save(e);
                notices.delete(n);
            }
        }
        for (FieldBookEntry e : entries.findBySavegameAndHarvestYear(sg, year)) {
            if (e.isHeld()) {
                e.setHeld(false);
                entries.save(e);
                resolve(sg, e);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<FieldBookEntry> all(Savegame sg) {
        return entries.findBySavegameOrderByIdAsc(sg);
    }

    @Transactional(readOnly = true)
    public List<Integer> closedYears(Savegame sg) {
        return years.findBySavegameOrderByHarvestYearAsc(sg).stream().map(FieldBookYear::getHarvestYear).toList();
    }

    @Transactional(readOnly = true)
    public List<FieldBookNotice> notices(Savegame sg) {
        return notices.findBySavegameOrderByIdAsc(sg);
    }

    public boolean closed(Savegame sg, Integer year) {
        return year != null && years.findBySavegameAndHarvestYear(sg, year).isPresent();
    }

    private FieldBookEntry own(Savegame sg, long id) {
        return entries.findById(id).filter(e -> e.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Feldbuch-Eintrag " + id + " nicht gefunden"));
    }

    private void requireOpen(Savegame sg, FieldBookEntry e) {
        if (e.isHeld() || closed(sg, e.getHarvestYear())) {
            throw new BusinessRuleException("FIELD_BOOK_YEAR_CLOSED", "Das Erntejahr " + e.getHarvestYear()
                    + " ist abgeschlossen. Öffne es wieder, um etwas zu ändern.");
        }
    }

    private static String text(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Boolean bool(String v) {
        if (v == null) {
            return null;
        }
        if (!"true".equals(v) && !"false".equals(v)) {
            throw new BusinessRuleException("FIELD_BOOK_VALUE", "Erwartet: true oder false.");
        }
        return Boolean.valueOf(v);
    }

    private static Double liters(String v) {
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException ex) {
            throw new BusinessRuleException("FIELD_BOOK_LITERS", "Liter müssen eine Zahl ab 0 sein.");
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static double nzd(Double v) {
        return v == null ? 0 : v;
    }
}
