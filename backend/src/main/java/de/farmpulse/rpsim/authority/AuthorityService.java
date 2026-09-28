package de.farmpulse.rpsim.authority;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.LivestockService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.HusbandryRecord;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.field.FieldYearClosedEvent;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.FieldCropHistoryRepository;
import de.farmpulse.rpsim.repository.HusbandryRecordRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-E2: the authority checks only what the export measures.
 * <ul>
 *   <li>Rotation (end of every FS25 year, crop history of C1): a field with the same crop as the year before gets a
 *   notice; the rotation premium (SUBSIDY per hectare for fields with another crop than the year before) is cut when a
 *   field repeats after a notice.</li>
 *   <li>Cultivation duty: an own field without crop for duty-months with weeds or stones.</li>
 *   <li>Animal welfare: a stable with health below the threshold or empty food / water for welfare-days.</li>
 * </ul>
 * Inspections are announced (mail) and decided after inspection-days - the player can always react. Cultivation:
 * still violated = fine. Animal welfare: the first violation brings a requirement with a new deadline, a repeated one a
 * fine and a loss of village reputation. At most max-inspections-per-month announcements.
 */
@Service
public class AuthorityService {

    public static final String RELATED = "AUTHORITY";
    public static final String DUTY = "CULTIVATION_DUTY";
    public static final String WELFARE = "ANIMAL_WELFARE";

    private final SavegameRepository savegames;
    private final ServiceCaseRepository cases;
    private final FieldCropHistoryRepository history;
    private final HusbandryRecordRepository husbandries;
    private final FieldService fields;
    private final FactsService facts;
    private final LivestockService livestock;
    private final CharacterLookup lookup;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public AuthorityService(SavegameRepository savegames, ServiceCaseRepository cases, FieldCropHistoryRepository history,
                            HusbandryRecordRepository husbandries, FieldService fields, FactsService facts,
                            LivestockService livestock, CharacterLookup lookup, OutboxService outbox,
                            NarrationRequestService narration, TrustScoreService trust, PublicActionService publicActions,
                            DiaryService diary, RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.cases = cases;
        this.history = history;
        this.husbandries = husbandries;
        this.fields = fields;
        this.facts = facts;
        this.livestock = livestock;
        this.lookup = lookup;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Authority cfg() {
        return props.getFormulas().getAuthority();
    }

    private Optional<Character> authority(Savegame sg) {
        return lookup.mandatory(sg, CharacterRole.AUTHORITY);
    }

    // ------------------------------------------------------------------------------------------ rotation

    /** Main crop of a field in a year: a harvested one first, otherwise the first seen. */
    Optional<String> crop(Savegame sg, int farmlandId, int year) {
        return history.findBySavegameAndFarmlandIdOrderByCropYearAsc(sg, farmlandId).stream()
                .filter(h -> h.getCropYear() == year)
                .min(Comparator.comparing((FieldCropHistory h) -> !h.isHarvested())
                        .thenComparingLong(FieldCropHistory::getFirstSeenGameTime))
                .map(FieldCropHistory::getFruitType);
    }

    @EventListener
    @Transactional
    public void onYearClosed(FieldYearClosedEvent e) {
        rotation(savegames.findById(e.savegameId()).orElseThrow(), e.year());
    }

    /** Result of the rotation check of a year: premium paid (after a cut) and the fields with the same crop again. */
    public record RotationResult(double premiumHectares, long premium, boolean cut, List<Integer> repeatedFields) {
    }

    @Transactional
    public RotationResult rotation(Savegame sg, int year) {
        Optional<Character> authority = authority(sg);
        if (!cfg().isEnabled() || authority.isEmpty()) {
            return new RotationResult(0, 0, false, List.of());
        }
        double hectares = 0;
        boolean cut = false;
        List<Integer> repeated = new java.util.ArrayList<>();
        for (FieldRecord r : fields.records(sg)) {
            Optional<String> now = crop(sg, r.getFarmlandId(), year);
            Optional<String> before = crop(sg, r.getFarmlandId(), year - 1);
            if (now.isEmpty() || before.isEmpty()) {
                continue;
            }
            if (!now.get().equals(before.get())) {
                hectares += r.getHectares() == null ? 0 : r.getHectares();
                continue;
            }
            r.setRotationViolations(r.getRotationViolations() + 1);
            repeated.add(r.getFarmlandId());
            cut |= r.getRotationViolations() >= 2;
            narration.request(sg, NarrationEventType.AUTHORITY_ROTATION_NOTICE).from(authority.get())
                    .facts(NarrationFacts.builder().put("fieldName", r.getFieldName()).put("fruitType", now.get())
                            .put("repeated", r.getRotationViolations() >= 2).build())
                    .category(CommunicationCategory.CONTRACT).submit();
        }
        long premium = Math.round(hectares * cfg().getRotationPremiumPerHa() * (cut ? 1 - cfg().getRotationCutShare() : 1));
        if (premium > 0) {
            outbox.money(sg, premium, MoneyReason.SUBSIDY, "Fruchtfolgeprämie Jahr " + year, null);
            narration.request(sg, NarrationEventType.AUTHORITY_SUBSIDY).from(authority.get())
                    .facts(NarrationFacts.builder().put("harvestYear", year)
                            .put("hectares", Math.round(hectares * 10) / 10.0).put("premium", premium)
                            .put("cut", cut).build())
                    .category(CommunicationCategory.CONTRACT).submit();
            diary.addAuto(sg, "MARKET", "Fruchtfolgeprämie Jahr " + year, premium + " € für "
                    + Math.round(hectares * 10) / 10.0 + " ha mit Fruchtwechsel" + (cut ? " (gekürzt)." : "."), null, null);
        }
        return new RotationResult(hectares, premium, cut, repeated);
    }

    // ------------------------------------------------------------------------------------------ inspections

    boolean mayAnnounce(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        if (sg.getAuthorityMonth() == null || sg.getAuthorityMonth() != month) {
            sg.setAuthorityMonth(month);
            sg.setAuthorityCount(0);
        }
        return sg.getAuthorityCount() < cfg().getMaxInspectionsPerMonth();
    }

    List<ServiceCase> inspections(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION));
    }

    /**
     * True while an inspection of the subject runs, or when the last one was decided less than {@code gapMs} ago (a
     * violation after a decision has to last the full time again before the next inspection).
     */
    private boolean inspected(Savegame sg, String rule, String subject, long gapMs) {
        long now = sg.getCurrentGameTime();
        return inspections(sg).stream().filter(c -> rule.equals(c.getTitle()) && subject.equals(c.getReference()))
                .anyMatch(c -> c.getStatus() == CaseStatus.IN_PROGRESS
                        || (c.getClosedAtGameTime() != null && now - c.getClosedAtGameTime() < gapMs));
    }

    ServiceCase announce(Savegame sg, Character authority, String rule, String subject, Integer farmlandId, String label) {
        long now = sg.getCurrentGameTime();
        sg.setAuthorityCount(sg.getAuthorityCount() + 1);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.AUTHORITY_INSPECTION);
        sc.setStatus(CaseStatus.IN_PROGRESS);
        sc.setCharacter(authority);
        sc.setTitle(rule);
        sc.setReference(subject);
        sc.setFarmlandId(farmlandId);
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getInspectionDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.AUTHORITY_INSPECTION_NOTICE).from(authority)
                .facts(NarrationFacts.builder().put("rule", rule).put("subject", label)
                        .put("inspectionDays", Math.round(cfg().getInspectionDays())).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, sc.getId())
                .formLink("/contracts?case=" + sc.getId()).submit();
        return sc;
    }

    @EventListener
    @Order(79)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Optional<Character> authority = authority(sg);
        if (!cfg().isEnabled() || authority.isEmpty()) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        Map<String, Boolean> welfareBad = trackWelfare(sg, f);
        decide(sg, welfareBad, f);
        if (sg.isFieldsTracked()) {
            for (FieldRecord r : fields.records(sg)) {
                String subject = String.valueOf(r.getFarmlandId());
                long gap = Math.round(cfg().getDutyMonths() * gameTime.msPerMonth(sg));
                if (dutyViolated(sg, r) && !inspected(sg, DUTY, subject, gap) && mayAnnounce(sg)) {
                    announce(sg, authority.get(), DUTY, subject, r.getFarmlandId(), "Feld " + r.getFieldName());
                }
            }
        }
        for (HusbandryRecord h : husbandries.findBySavegame(sg)) {
            if (h.getBadSince() != null && GameTime.toDays(sg.getCurrentGameTime() - h.getBadSince()) >= cfg().getWelfareDays()
                    && !inspected(sg, WELFARE, h.getHusbandryUniqueId(), GameTime.days(cfg().getWelfareDays()))
                    && mayAnnounce(sg)) {
                announce(sg, authority.get(), WELFARE, h.getHusbandryUniqueId(), null, stableLabel(f, h.getHusbandryUniqueId()));
            }
        }
    }

    /** Cultivation duty: no crop for duty-months and weeds or stones above the thresholds of C4. */
    boolean dutyViolated(Savegame sg, FieldRecord r) {
        return r.getPhase().bare() && (r.getWeedsHighSince() != null || r.getStonesHighSince() != null)
                && (sg.getCurrentGameTime() - r.getPhaseSinceGameTime()) / (double) gameTime.msPerMonth(sg) >= cfg().getDutyMonths();
    }

    /** Animal welfare: health below the threshold or food / water empty. Tracks since when; returns the current state. */
    Map<String, Boolean> trackWelfare(Savegame sg, FarmFacts f) {
        Map<String, Boolean> bad = new HashMap<>();
        if (f == null || f.husbandries() == null) {
            return bad;
        }
        Map<String, HusbandryRecord> records = new HashMap<>();
        husbandries.findBySavegame(sg).forEach(r -> records.put(r.getHusbandryUniqueId(), r));
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            if (h == null || h.husbandryUniqueId() == null) {
                continue;
            }
            Double water = livestock.water(h);
            boolean isBad = (h.health() != null && h.health() < cfg().getWelfareHealthThreshold())
                    || (h.food() != null && h.food() <= 0) || (water != null && water <= 0);
            bad.put(h.husbandryUniqueId(), isBad);
            HusbandryRecord r = records.computeIfAbsent(h.husbandryUniqueId(), id -> {
                HusbandryRecord n = new HusbandryRecord();
                n.setSavegame(sg);
                n.setHusbandryUniqueId(id);
                return n;
            });
            if (isBad && (r.getBadSince() == null || r.getBadSince() > sg.getCurrentGameTime())) {
                r.setBadSince(sg.getCurrentGameTime());
            } else if (!isBad) {
                r.setBadSince(null);
            }
            husbandries.save(r);
        }
        return bad;
    }

    /** Inspections whose deadline passed: still violated = requirement / fine, otherwise closed without objection. */
    void decide(Savegame sg, Map<String, Boolean> welfareBad, FarmFacts f) {
        long now = sg.getCurrentGameTime();
        Map<String, HusbandryRecord> records = new HashMap<>();
        husbandries.findBySavegame(sg).forEach(r -> records.put(r.getHusbandryUniqueId(), r));
        Map<Integer, FieldRecord> fieldRecords = new HashMap<>();
        fields.records(sg).forEach(r -> fieldRecords.put(r.getFarmlandId(), r));
        for (ServiceCase sc : inspections(sg)) {
            if (sc.getStatus() != CaseStatus.IN_PROGRESS || sc.getDeadlineGameTime() == null || sc.getDeadlineGameTime() > now) {
                continue;
            }
            if (DUTY.equals(sc.getTitle())) {
                FieldRecord r = fieldRecords.get(sc.getFarmlandId());
                if (r != null && dutyViolated(sg, r)) {
                    r.setDutyViolations(r.getDutyViolations() + 1);
                    fine(sg, sc, cfg().getDutyFine(), 0, "Feld " + r.getFieldName());
                } else {
                    resolve(sg, sc, "IN_ORDER", 0, "Feld " + (r == null ? sc.getReference() : r.getFieldName()));
                }
            } else if (WELFARE.equals(sc.getTitle())) {
                String label = stableLabel(f, sc.getReference());
                HusbandryRecord r = records.get(sc.getReference());
                if (!Boolean.TRUE.equals(welfareBad.get(sc.getReference())) || r == null) {
                    resolve(sg, sc, "IN_ORDER", 0, label);
                } else if (r.getViolations() == 0) {
                    // first violation: requirement with a new deadline
                    r.setViolations(1);
                    sc.setRoundsUsed(sc.getRoundsUsed() + 1);
                    sc.setDeadlineGameTime(now + GameTime.days(cfg().getInspectionDays()));
                    trust.recordEvent(sc.getCharacter(), cfg().getViolationTrustDelta(), TrustReason.AUTHORITY_VIOLATION,
                            "Tierwohl-Auflage " + label);
                    result(sg, sc, "REQUIREMENT", 0, label);
                } else {
                    r.setViolations(r.getViolations() + 1);
                    fine(sg, sc, cfg().getWelfareFine(), cfg().getWelfareReputationDelta(), label);
                }
            }
        }
    }

    private void fine(Savegame sg, ServiceCase sc, long fine, double reputationDelta, String label) {
        outbox.money(sg, -fine, MoneyReason.FINE, "Bußgeld " + label, new Related(RELATED, sc.getId()));
        trust.recordEvent(sc.getCharacter(), cfg().getViolationTrustDelta(), TrustReason.AUTHORITY_VIOLATION, "Bußgeld " + label);
        if (reputationDelta != 0) {
            publicActions.record(sg, PublicActionType.AUTHORITY_FINE, reputationDelta, "Bußgeld Tierwohl " + label);
        }
        sc.setCostAmount(fine);
        resolve(sg, sc, "FINED", fine, label);
        diary.addAuto(sg, "OTHER", "Bußgeld vom Amt", fine + " € wegen " + (WELFARE.equals(sc.getTitle())
                ? "Tierwohl" : "Bewirtschaftungspflicht") + " (" + label + ").", RELATED, sc.getId());
    }

    private void resolve(Savegame sg, ServiceCase sc, String result, long fine, String label) {
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution(result);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        result(sg, sc, result, fine, label);
    }

    private void result(Savegame sg, ServiceCase sc, String result, long fine, String label) {
        narration.request(sg, NarrationEventType.AUTHORITY_INSPECTION_RESULT).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("rule", sc.getTitle()).put("subject", label).put("result", result)
                        .put("fine", fine > 0 ? fine : null)
                        .put("inspectionDays", "REQUIREMENT".equals(result) ? Math.round(cfg().getInspectionDays()) : null)
                        .build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, sc.getId()).submit();
    }

    static String stableLabel(FarmFacts f, String husbandryId) {
        if (f != null && f.assets() != null && f.assets().animals() != null) {
            for (BridgeDtos.Animal a : f.assets().animals()) {
                if (a != null && husbandryId.equals(a.husbandryUniqueId()) && a.type() != null) {
                    return "Stall (" + a.type() + ")";
                }
            }
        }
        return "Stall";
    }
}
