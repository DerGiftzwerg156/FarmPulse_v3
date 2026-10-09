package de.farmpulse.rpsim.authority;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.HusbandryRecord;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.HusbandryRecordRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B3: fertiliser rules (owner decisions 2026-10-05 in QUESTIONS.md).
 * <ul>
 *   <li>Closed period (closed-periods, November-January): an own field (not grassland, excluded-fruit-types) whose
 *   exported {@code sprayType} is organic (LIQUID_MANURE / MANURE) while its {@code sprayLevel} rose since the last
 *   export was fertilised organically - the authority announces an inspection (switch FERTILIZER). Without
 *   {@code sprayType} (older mod) or with require-spray-type false (fallback of the manual test plan) every rise of
 *   {@code sprayLevel} counts and the authority writes "Düngung festgestellt".</li>
 *   <li>Decision after authority.inspection-days: the first finding of the savegame is a warning (trust of the
 *   authority -3), every later one a fine (FINE, idyllic factor) and a loss of village reputation.</li>
 *   <li>Slurry store (condition by slurry-condition-titles, R2-A7): from slurry-warning-ratio for slurry-warning-days
 *   the animal keeper - without one the cooperative - warns (at most every slurry-warning-cooldown-days). At the start
 *   of reminder-period (October) farms with a store from reminder-min-ratio are reminded of the closed period.</li>
 * </ul>
 */
@Service
public class FertilizerRulesService {

    public static final String RULE = "FERTILIZER_RULES";
    /** ServiceCase.direction of a finding: organic fertiliser recognised / only a rise of the spray level. */
    public static final String ORGANIC = "ORGANIC";
    public static final String UNKNOWN = "UNKNOWN";

    private final SavegameRepository savegames;
    private final ServiceCaseRepository cases;
    private final HusbandryRecordRepository husbandries;
    private final EmployeeRepository employees;
    private final FieldService fields;
    private final FactsService facts;
    private final AuthorityService authority;
    private final BurdeningEvents burden;
    private final CharacterLookup lookup;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public FertilizerRulesService(SavegameRepository savegames, ServiceCaseRepository cases,
                                  HusbandryRecordRepository husbandries, EmployeeRepository employees, FieldService fields,
                                  FactsService facts, AuthorityService authority, BurdeningEvents burden,
                                  CharacterLookup lookup, OutboxService outbox, NarrationRequestService narration,
                                  TrustScoreService trust, PublicActionService publicActions, DiaryService diary,
                                  RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.cases = cases;
        this.husbandries = husbandries;
        this.employees = employees;
        this.fields = fields;
        this.facts = facts;
        this.authority = authority;
        this.burden = burden;
        this.lookup = lookup;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.FertilizerRules cfg() {
        return props.getFormulas().getFertilizerRules();
    }

    /** Kind of a finding: ORGANIC, UNKNOWN (fallback) or empty (no finding). */
    public static Optional<String> finding(String type, Integer level, Integer lastLevel, RpsimProperties.FertilizerRules cfg) {
        if (level == null || lastLevel == null || level <= lastLevel) {
            return Optional.empty();
        }
        if (type == null || !cfg.isRequireSprayType()) {
            return Optional.of(UNKNOWN);
        }
        return cfg.getOrganicSprayTypes().contains(type) ? Optional.of(ORGANIC) : Optional.empty();
    }

    // ------------------------------------------------------------------------------------------ closed period

    /** Every export: compare spray type and level of the own fields with the last export (after FieldService). */
    @EventListener
    @Order(87)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null) {
            return;
        }
        Map<Integer, FieldRecord> records = new HashMap<>();
        fields.records(sg).forEach(r -> records.put(r.getFarmlandId(), r));
        boolean closed = cfg().isEnabled() && cfg().getClosedPeriods().contains(gameTime.periodOfYear(sg, e.gameTime()));
        for (BridgeDtos.Field field : f.fields()) {
            FieldRecord r = field == null || field.farmlandId() == null ? null : records.get(field.farmlandId());
            if (r == null) {
                continue;
            }
            if (closed && (field.fruitType() == null || !cfg().getExcludedFruitTypes().contains(field.fruitType()))) {
                finding(field.sprayType(), field.sprayLevel(), r.getLastSprayLevel(), cfg())
                        .ifPresent(kind -> detected(sg, r, kind));
            }
            // Roadmap V3.1 R31-D6: slurry / manure spread in any period - the guests of the farm holidays smell it
            if (field.sprayType() != null && cfg().getOrganicSprayTypes().contains(field.sprayType())
                    && field.sprayLevel() != null && r.getLastSprayLevel() != null && field.sprayLevel() > r.getLastSprayLevel()) {
                sg.setLastOrganicSpreadGameTime(e.gameTime());
            }
            r.setLastSprayType(field.sprayType());
            r.setLastSprayLevel(field.sprayLevel());
        }
    }

    boolean running(Savegame sg, String subject) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION)).stream()
                .anyMatch(c -> RULE.equals(c.getTitle()) && subject.equals(c.getReference())
                        && c.getStatus() == CaseStatus.IN_PROGRESS);
    }

    Optional<ServiceCase> detected(Savegame sg, FieldRecord r, String kind) {
        Optional<Character> amt = lookup.mandatory(sg, CharacterRole.AUTHORITY);
        String subject = String.valueOf(r.getFarmlandId());
        if (!burden.on(sg, Burden.FERTILIZER) || amt.isEmpty() || running(sg, subject) || !authority.mayAnnounce(sg)) {
            return Optional.empty();
        }
        ServiceCase sc = authority.announce(sg, amt.get(), RULE, subject, r.getFarmlandId(), label(r.getFieldName(), kind));
        sc.setDirection(kind);
        return Optional.of(sc);
    }

    static String label(String fieldName, String kind) {
        return "Feld " + fieldName + (ORGANIC.equals(kind) ? " – Gülle/Mist in der Sperrfrist" : " – Düngung festgestellt");
    }

    /** Daily: inspections whose deadline passed - first finding a warning, later ones a fine. */
    @EventListener
    @Order(80)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION))) {
            if (RULE.equals(sc.getTitle()) && sc.getStatus() == CaseStatus.IN_PROGRESS && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() <= now) {
                decide(sg, sc);
            }
        }
        slurry(sg, facts.latest(sg).orElse(null));
    }

    void decide(Savegame sg, ServiceCase sc) {
        String name = fields.records(sg).stream().filter(r -> sc.getFarmlandId() != null && r.getFarmlandId() == sc.getFarmlandId())
                .map(FieldRecord::getFieldName).findFirst().orElse(sc.getReference());
        String label = label(name, sc.getDirection());
        trust.recordEvent(sc.getCharacter(), props.getFormulas().getAuthority().getViolationTrustDelta(),
                TrustReason.AUTHORITY_VIOLATION, "Düngeverordnung " + label);
        boolean first = sg.getFertilizerViolations() == 0;
        sg.setFertilizerViolations(sg.getFertilizerViolations() + 1);
        if (first) {
            authority.resolve(sg, sc, "WARNING", 0, label);
            diary.addAuto(sg, "OTHER", "Verwarnung vom Amt", "Düngeverordnung: " + label + ". Im Wiederholungsfall droht ein "
                    + "Bußgeld.", AuthorityService.RELATED, sc.getId());
            return;
        }
        long fine = Math.round(cfg().getFine() * burden.factor(sg));
        outbox.money(sg, -fine, MoneyReason.FINE, "Bußgeld Düngeverordnung", new Related(AuthorityService.RELATED, sc.getId()));
        if (cfg().getReputationDelta() != 0) {
            publicActions.record(sg, PublicActionType.AUTHORITY_FINE, cfg().getReputationDelta(), "Bußgeld Düngeverordnung " + label);
        }
        sc.setCostAmount(fine);
        authority.resolve(sg, sc, "FINED", fine, label);
        diary.addAuto(sg, "OTHER", "Bußgeld vom Amt", fine + " € wegen Düngeverordnung (" + label + ").",
                AuthorityService.RELATED, sc.getId());
    }

    // ------------------------------------------------------------------------------------------ slurry store

    /** Slurry ratio of a stable (first condition with a configured title), null when not reported. */
    public Double slurryRatio(BridgeDtos.Husbandry h) {
        if (h == null || h.conditions() == null) {
            return null;
        }
        for (BridgeDtos.HusbandryCondition c : h.conditions()) {
            if (c != null && c.title() != null && c.ratio() != null
                    && cfg().getSlurryConditionTitles().stream().anyMatch(t -> t.equalsIgnoreCase(c.title().strip()))) {
                return c.ratio();
            }
        }
        return null;
    }

    /** The animal keeper (first active one) or, without one, the cooperative. */
    Optional<Character> sender(Savegame sg) {
        Optional<Character> keeper = employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.ANIMAL_KEEPER)
                .stream().findFirst().map(x -> x.getCharacter());
        return keeper.isPresent() ? keeper : lookup.mandatory(sg, CharacterRole.COOPERATIVE);
    }

    void slurry(Savegame sg, FarmFacts f) {
        if (!cfg().isEnabled() || f == null || f.husbandries() == null) {
            return;
        }
        long now = sg.getCurrentGameTime();
        Map<String, HusbandryRecord> records = new HashMap<>();
        husbandries.findBySavegame(sg).forEach(r -> records.put(r.getHusbandryUniqueId(), r));
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            Double ratio = slurryRatio(h);
            if (ratio == null || h.husbandryUniqueId() == null) {
                continue;
            }
            HusbandryRecord r = records.computeIfAbsent(h.husbandryUniqueId(), id -> {
                HusbandryRecord n = new HusbandryRecord();
                n.setSavegame(sg);
                n.setHusbandryUniqueId(id);
                return husbandries.save(n);
            });
            if (ratio < cfg().getSlurryWarningRatio()) {
                r.setSlurryHighSince(null);
                continue;
            }
            if (r.getSlurryHighSince() == null || r.getSlurryHighSince() > now) {
                r.setSlurryHighSince(now);
            }
            boolean cooled = r.getLastSlurryWarningGameTime() == null
                    || GameTime.toDays(now - r.getLastSlurryWarningGameTime()) >= cfg().getSlurryWarningCooldownDays();
            if (GameTime.toDays(now - r.getSlurryHighSince()) >= cfg().getSlurryWarningDays() && cooled) {
                Optional<Character> from = sender(sg);
                if (from.isEmpty()) {
                    continue;
                }
                r.setLastSlurryWarningGameTime(now);
                narration.request(sg, NarrationEventType.SLURRY_WARNING).from(from.get())
                        .facts(NarrationFacts.builder().put("stable", AuthorityService.stableLabel(f, h.husbandryUniqueId()))
                                .put("percent", Math.round(ratio * 100))
                                .put("closedFrom", CalendarText.month(cfg().getClosedPeriods().getFirst())).build())
                        .category(CommunicationCategory.LIVESTOCK).submit();
            }
        }
    }

    /** Month start of reminder-period: farms with a slurry store from reminder-min-ratio are reminded. */
    @EventListener
    @Order(80)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || gameTime.periodOfYear(sg, sg.getCurrentGameTime()) != cfg().getReminderPeriod()) {
            return;
        }
        reminder(sg, facts.latest(sg).orElse(null));
    }

    boolean reminder(Savegame sg, FarmFacts f) {
        if (f == null || f.husbandries() == null) {
            return false;
        }
        List<String> stables = new ArrayList<>();
        int max = 0;
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            Double ratio = slurryRatio(h);
            if (ratio != null && ratio >= cfg().getReminderMinRatio()) {
                stables.add(AuthorityService.stableLabel(f, h.husbandryUniqueId()));
                max = Math.max(max, (int) Math.round(ratio * 100));
            }
        }
        Optional<Character> from = sender(sg);
        if (stables.isEmpty() || from.isEmpty()) {
            return false;
        }
        narration.request(sg, NarrationEventType.SLURRY_REMINDER).from(from.get())
                .facts(NarrationFacts.builder().put("stables", String.join(", ", stables)).put("percent", max)
                        .put("closedFrom", CalendarText.month(cfg().getClosedPeriods().getFirst())).build())
                .category(CommunicationCategory.LIVESTOCK).submit();
        return true;
    }
}
