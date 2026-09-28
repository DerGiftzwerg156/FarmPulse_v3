package de.farmpulse.rpsim.field;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-C4 / R2-C6: the village reacts to the real fields. Once per game day:
 * <ul>
 *   <li>neighbor - weeds / stones above the threshold for neighbor-after-months: a friendly message, still there after
 *   neighbor-repeat-months: an annoyed one with a small trust loss (weeds and stones only when the savegame has them)</li>
 *   <li>gossip - a field without a crop for fallow-gossip-months, or a withered crop (once per episode)</li>
 *   <li>congratulation - at the end of an FS25 year every harvestable field was harvested and nothing withered</li>
 *   <li>hints of the cooperative - "Feld 7 ist erntereif", lime / plowing missing on a bare field; at most one per
 *   hint-cooldown-days, switchable per savegame</li>
 * </ul>
 * Neighbor, gossip and congratulation share the cap max-messages-per-month. Without the field export nothing happens.
 */
@Service
public class FieldReactionService {

    public enum Topic { WEEDS, STONES }

    public enum Hint { HARVEST_READY, LIME, PLOW }

    private final SavegameRepository savegames;
    private final FieldService fields;
    private final FactsService facts;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public FieldReactionService(SavegameRepository savegames, FieldService fields, FactsService facts,
                                CharacterLookup lookup, NarrationRequestService narration, TrustScoreService trust,
                                DiaryService diary, RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.fields = fields;
        this.facts = facts;
        this.lookup = lookup;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Fields cfg() {
        return props.getFormulas().getFields();
    }

    @EventListener
    @Order(62)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!sg.isFieldsTracked()) {
            return;
        }
        List<FieldRecord> records = fields.records(sg);
        neighbor(sg, records);
        gossip(sg, records);
        hint(sg, records, facts.latest(sg).orElse(null));
    }

    @EventListener
    @Transactional
    public void onYearClosed(FieldYearClosedEvent e) {
        congratulate(savegames.findById(e.savegameId()).orElseThrow(), e.year());
    }

    // ------------------------------------------------------------------------------------------ monthly cap

    boolean mayTalk(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        if (sg.getFieldMessagesMonth() == null || sg.getFieldMessagesMonth() != month) {
            sg.setFieldMessagesMonth(month);
            sg.setFieldMessagesCount(0);
        }
        return sg.getFieldMessagesCount() < cfg().getMaxMessagesPerMonth();
    }

    private void talked(Savegame sg) {
        sg.setFieldMessagesCount(sg.getFieldMessagesCount() + 1);
    }

    private double monthsSince(Savegame sg, long since) {
        return (sg.getCurrentGameTime() - since) / (double) gameTime.msPerMonth(sg);
    }

    // ------------------------------------------------------------------------------------------ C4 neighbor

    /** Friendly message after neighbor-after-months, annoyed one (trust loss) neighbor-repeat-months later. */
    @Transactional
    public int neighbor(Savegame sg, List<FieldRecord> records) {
        Optional<Character> neighbor = lookup.firstActive(sg, CharacterRole.NEIGHBOR_FARMER);
        if (neighbor.isEmpty()) {
            return 0;
        }
        int sent = 0;
        long now = sg.getCurrentGameTime();
        for (FieldRecord r : records) {
            Topic topic = r.getWeedsHighSince() != null ? Topic.WEEDS : r.getStonesHighSince() != null ? Topic.STONES : null;
            if (topic == null || r.getNeighborWarnings() >= 2 || !mayTalk(sg)) {
                continue;
            }
            long since = topic == Topic.WEEDS ? r.getWeedsHighSince() : r.getStonesHighSince();
            boolean friendly = r.getNeighborWarnings() == 0 && monthsSince(sg, since) >= cfg().getNeighborAfterMonths();
            boolean annoyed = r.getNeighborWarnings() == 1 && r.getLastNeighborWarningGameTime() != null
                    && monthsSince(sg, r.getLastNeighborWarningGameTime()) >= cfg().getNeighborRepeatMonths();
            if (!friendly && !annoyed) {
                continue;
            }
            r.setNeighborWarnings(r.getNeighborWarnings() + 1);
            r.setLastNeighborWarningGameTime(now);
            if (annoyed) {
                trust.recordEvent(neighbor.get(), cfg().getNeighborTrustDelta(), TrustReason.FIELD_NEGLECTED,
                        "Feld " + r.getFieldName() + (topic == Topic.WEEDS ? " verunkrautet" : " voller Steine"));
            }
            narration.request(sg, NarrationEventType.FIELD_NEIGHBOR_COMPLAINT).from(neighbor.get())
                    .facts(NarrationFacts.builder().put("fieldName", r.getFieldName()).put("topic", topic)
                            .put("stage", annoyed ? "NEIGHBOR_ANNOYED" : "NEIGHBOR_FRIENDLY")
                            .put("fruitType", r.getFruitType()).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).submit();
            talked(sg);
            sent++;
        }
        return sent;
    }

    // ------------------------------------------------------------------------------------------ C4 gossip

    /** Village gossip about a withered crop or a long fallow field - at most one per day, once per episode. */
    @Transactional
    public boolean gossip(Savegame sg, List<FieldRecord> records) {
        for (FieldRecord r : records) {
            boolean withered = r.getPhase() == FieldPhase.WITHERED && !r.isWitheredGossipSent();
            boolean fallow = r.getPhase().bare() && !r.isFallowGossipSent()
                    && monthsSince(sg, r.getPhaseSinceGameTime()) >= cfg().getFallowGossipMonths();
            if (!withered && !fallow) {
                continue;
            }
            Optional<Character> teller = villager(sg);
            if (teller.isEmpty() || !mayTalk(sg)) {
                return false;
            }
            if (withered) {
                r.setWitheredGossipSent(true);
            } else {
                r.setFallowGossipSent(true);
            }
            narration.request(sg, NarrationEventType.FIELD_GOSSIP).from(teller.get())
                    .facts(NarrationFacts.builder().put("fieldName", r.getFieldName())
                            .put("topic", withered ? "WITHERED" : "FALLOW").put("fruitType", r.getFruitType()).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).submit();
            talked(sg);
            return true;
        }
        return false;
    }

    private Optional<Character> villager(Savegame sg) {
        List<Character> dyn = lookup.activeDynamic(sg);
        return dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER, CharacterRole.NEIGHBOR_FARMER)
                : Optional.of(random.pick(dyn));
    }

    // ------------------------------------------------------------------------------------------ C4 congratulation

    /** Every crop of the ended FS25 year that became harvestable was harvested, none withered, at least one harvest. */
    @Transactional
    public boolean congratulate(Savegame sg, int year) {
        List<FieldCropHistory> crops = fields.cropsOfYear(sg, year);
        boolean anyHarvest = crops.stream().anyMatch(FieldCropHistory::isHarvested);
        boolean allInTime = crops.stream().noneMatch(FieldCropHistory::isWithered)
                && crops.stream().filter(FieldCropHistory::isHarvestableSeen).allMatch(FieldCropHistory::isHarvested);
        if (!anyHarvest || !allInTime || !mayTalk(sg)) {
            return false;
        }
        Optional<Character> cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return false;
        }
        long harvested = crops.stream().filter(FieldCropHistory::isHarvested).count();
        trust.recordEvent(cooperative.get(), cfg().getHarvestCongratulationTrustDelta(), TrustReason.HARVEST_IN_TIME,
                "Ernte " + year + " vollständig eingebracht");
        narration.request(sg, NarrationEventType.FIELD_HARVEST_CONGRATULATION).from(cooperative.get())
                .facts(NarrationFacts.builder().put("harvestYear", year).put("harvestedFields", harvested).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        diary.addAuto(sg, "MARKET", "Ernte " + year + " eingebracht",
                harvested + " Ernten rechtzeitig eingefahren, nichts ist verdorrt.", null, null);
        talked(sg);
        return true;
    }

    // ------------------------------------------------------------------------------------------ C6 hints

    /**
     * One hint of the cooperative per cooldown: a harvestable field first, then lime (limeLevel 0) and plowing
     * (plowLevel 0) on a field without a growing crop - lime / plowing only when the savegame requires them. Every
     * hint once per episode.
     */
    @Transactional
    public Optional<Hint> hint(Savegame sg, List<FieldRecord> records, FarmFacts f) {
        if (!cfg().isHintsEnabled() || !sg.isFieldHintsEnabled() || f == null || f.fields() == null) {
            return Optional.empty();
        }
        Long last = sg.getLastFieldHintGameTime();
        if (last != null && last <= sg.getCurrentGameTime()
                && GameTime.toDays(sg.getCurrentGameTime() - last) < cfg().getHintCooldownDays()) {
            return Optional.empty();
        }
        Map<Integer, BridgeDtos.Field> byFarmland = new HashMap<>();
        f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> byFarmland.put(x.farmlandId(), x));
        BridgeDtos.FieldRules rules = f.fieldRules();
        for (Hint kind : Hint.values()) {
            for (FieldRecord r : records) {
                BridgeDtos.Field field = byFarmland.get(r.getFarmlandId());
                if (field != null && due(kind, r, field, rules)) {
                    return send(sg, kind, r) ? Optional.of(kind) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    static boolean due(Hint kind, FieldRecord r, BridgeDtos.Field field, BridgeDtos.FieldRules rules) {
        return switch (kind) {
            case HARVEST_READY -> r.getPhase() == FieldPhase.HARVESTABLE && !r.isHarvestHintSent();
            case LIME -> rules != null && Boolean.TRUE.equals(rules.limeRequired()) && r.getPhase().bare()
                    && !r.isLimeHintSent() && field.limeLevel() != null && field.limeLevel() == 0;
            case PLOW -> rules != null && Boolean.TRUE.equals(rules.plowingRequired()) && r.getPhase().bare()
                    && !r.isPlowHintSent() && field.plowLevel() != null && field.plowLevel() == 0;
        };
    }

    private boolean send(Savegame sg, Hint kind, FieldRecord r) {
        Optional<Character> cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return false;
        }
        switch (kind) {
            case HARVEST_READY -> r.setHarvestHintSent(true);
            case LIME -> r.setLimeHintSent(true);
            case PLOW -> r.setPlowHintSent(true);
        }
        sg.setLastFieldHintGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.FIELD_WORK_HINT).from(cooperative.get())
                .facts(NarrationFacts.builder().put("fieldName", r.getFieldName()).put("hint", kind)
                        .put("fruitType", r.getFruitType()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        return true;
    }
}
