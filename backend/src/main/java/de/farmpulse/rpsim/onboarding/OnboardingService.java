package de.farmpulse.rpsim.onboarding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.BridgeSyncService;
import de.farmpulse.rpsim.bridge.DetectedSavegameRegistry;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterGeneratorService.Spec;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.LoanService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.domain.StoryHook;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageRelation;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.StoryHookRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Onboarding with two-phase linking (technical concept "Onboarding & Zwei-Phasen-Verknüpfung"):
 * (1) web wizard BEFORE the FS25 savegame exists: backstory building blocks, free text, start staff, tone, start
 * capital and optional legacy loan, preview with reroll; (2) the player creates/loads the savegame in FS25;
 * (3) the mod reports the new savegameId; (4) the web app lists unlinked ids for confirmation; (5) the savegame is
 * materialised, start employees are created and story hooks are scheduled.
 * <p>
 * Guardrail: the free text only feeds the optional AI enrichment, never numbers. Structured building blocks may
 * set real start values. Backstory profiles are never stored as reusable templates.
 */
@Service
public class OnboardingService {

    public static final List<CharacterRole> MANDATORY_ROLES = List.of(CharacterRole.BANK_ADVISOR,
            CharacterRole.COOPERATIVE, CharacterRole.AUTHORITY);
    public static final List<CharacterRole> DYNAMIC_ROLES = List.of(CharacterRole.LAND_AGENT,
            CharacterRole.NEIGHBOR_FARMER, CharacterRole.NEIGHBOR_FARMER, CharacterRole.VILLAGER, CharacterRole.SUPPLIER,
            CharacterRole.VILLAGER, CharacterRole.NEIGHBOR_FARMER, CharacterRole.SUPPLIER);

    public record Request(FarmOrigin farmOrigin, VillageRelation villageRelation, String freeText, long startingCapitalTarget,
                          Long legacyLoanAmount, TonePreset tonePreset, List<JobRole> initialEmployees) {
    }

    public record EmployeeSlot(Long characterId, JobRole jobRole) {
    }

    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final TrustEventRepository trustEvents;
    private final StoryHookRepository hooks;
    private final CharacterGeneratorService generator;
    private final HiringService hiring;
    private final LoanService loans;
    private final OutboxService outbox;
    private final LiquidityService liquidity;
    private final NarrationRequestService narration;
    private final CharacterLookup lookup;
    private final DiaryService diary;
    private final DetectedSavegameRegistry detected;
    private final BridgeSyncService bridgeSync;
    private final ObjectProvider<FreeTextModerator> moderator;
    private final RandomSource random;
    private final RpsimProperties props;
    private final JsonMapper json;

    public OnboardingService(SavegameRepository savegames, CharacterRepository characters, TrustEventRepository trustEvents,
                             StoryHookRepository hooks, CharacterGeneratorService generator, HiringService hiring,
                             LoanService loans, OutboxService outbox, LiquidityService liquidity,
                             NarrationRequestService narration, CharacterLookup lookup, DiaryService diary,
                             DetectedSavegameRegistry detected, BridgeSyncService bridgeSync,
                             ObjectProvider<FreeTextModerator> moderator, RandomSource random, RpsimProperties props,
                             JsonMapper json) {
        this.savegames = savegames;
        this.characters = characters;
        this.trustEvents = trustEvents;
        this.hooks = hooks;
        this.generator = generator;
        this.hiring = hiring;
        this.loans = loans;
        this.outbox = outbox;
        this.liquidity = liquidity;
        this.narration = narration;
        this.lookup = lookup;
        this.diary = diary;
        this.detected = detected;
        this.bridgeSync = bridgeSync;
        this.moderator = moderator;
        this.random = random;
        this.props = props;
        this.json = json;
    }

    private RpsimProperties.Onboarding cfg() {
        return props.getFormulas().getOnboarding();
    }

    // ------------------------------------------------------------------------------------------ step 1

    @Transactional
    public Savegame create(Request r) {
        if (r.startingCapitalTarget() < 0) {
            throw new BusinessRuleException("INVALID_CAPITAL", "Das Startkapital darf nicht negativ sein.");
        }
        if (r.legacyLoanAmount() != null && r.legacyLoanAmount() < 0) {
            throw new BusinessRuleException("INVALID_LEGACY_LOAN", "Der Altlasten-Kredit darf nicht negativ sein.");
        }
        Savegame sg = new Savegame();
        sg.setStatus(SavegameStatus.DRAFT);
        sg.setTonePreset(r.tonePreset() == null ? TonePreset.REALISTIC : r.tonePreset());
        sg.setFarmOrigin(r.farmOrigin() == null ? FarmOrigin.BOUGHT_FRESH_START : r.farmOrigin());
        sg.setVillageRelation(r.villageRelation() == null ? VillageRelation.UNKNOWN : r.villageRelation());
        sg.setStartingCapitalTarget(r.startingCapitalTarget());
        sg.setLegacyLoanAmount(r.legacyLoanAmount() == null || r.legacyLoanAmount() == 0 ? null : r.legacyLoanAmount());
        sg.setCreatedAt(Instant.now());
        sg.setGenerationSeed(random.nextLong());
        applyFreeText(sg, r.freeText());
        savegames.save(sg);
        List<EmployeeSlot> slots = new ArrayList<>();
        generateCast(sg, r.initialEmployees() == null ? List.of() : r.initialEmployees(), slots);
        sg.setInitialEmployeesJson(json.writeValueAsString(slots));
        return sg;
    }

    /** Free text: only for AI enrichment; limited, moderated, silently dropped on a filter hit. */
    void applyFreeText(Savegame sg, String freeText) {
        String t = freeText == null ? "" : freeText.strip();
        if (t.length() > cfg().getMaxFreeTextLength()) {
            t = t.substring(0, cfg().getMaxFreeTextLength());
        }
        FreeTextModerator m = moderator.getIfAvailable();
        if (!t.isEmpty() && m != null && !m.accept(t)) {
            sg.setFreeTextRejected(true);
            t = "";
        }
        sg.setBackstoryFreeText(t.isEmpty() ? null : t);
    }

    private double startTrust(Savegame sg, CharacterCategory category, CharacterRole role) {
        boolean villager = category == CharacterCategory.DYNAMIC || role == CharacterRole.COOPERATIVE;
        if (!villager) {
            return 0;
        }
        return switch (sg.getVillageRelation()) {
            case STRAINED -> props.getFormulas().getTrust().getVillageRelationStrained();
            case CONNECTED -> props.getFormulas().getTrust().getVillageRelationConnected();
            case UNKNOWN -> 0; // neutral default without trust offset
        };
    }

    private Spec specFor(Savegame sg, CharacterRole role, CharacterCategory category, JobRole jobRole) {
        return new Spec(role, category, startTrust(sg, category, role), jobRole);
    }

    private void generateCast(Savegame sg, List<JobRole> employees, List<EmployeeSlot> slots) {
        for (CharacterRole role : MANDATORY_ROLES) {
            enrich(generator.generate(sg, specFor(sg, role, CharacterCategory.MANDATORY, null), random.nextLong()), sg);
        }
        for (int i = 0; i < cfg().getDynamicCharacters(); i++) {
            CharacterRole role = DYNAMIC_ROLES.get(i % DYNAMIC_ROLES.size());
            enrich(generator.generate(sg, specFor(sg, role, CharacterCategory.DYNAMIC, null), random.nextLong()), sg);
        }
        for (JobRole jr : employees) {
            Character c = generator.generate(sg, specFor(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, jr),
                    random.nextLong());
            enrich(c, sg);
            slots.add(new EmployeeSlot(c.getId(), jr));
        }
    }

    private void enrich(Character c, Savegame sg) {
        generator.enrich(c, sg.getBackstoryFreeText());
    }

    // ------------------------------------------------------------------------------------------ preview / reroll

    public record PreviewEntry(Long characterId, String name, CharacterRole role, CharacterCategory category, JobRole jobRole,
                               String shortDescription) {
    }

    @Transactional(readOnly = true)
    public List<PreviewEntry> preview(Savegame draft) {
        Savegame sg = savegames.findById(draft.getId()).orElseThrow();
        Map<Long, JobRole> jobRoles = new java.util.HashMap<>();
        slots(sg).forEach(s -> jobRoles.put(s.characterId(), s.jobRole()));
        return characters.findBySavegameOrderByIdAsc(sg).stream()
                .map(c -> new PreviewEntry(c.getId(), c.getName(), c.getRole(), c.getCategory(), jobRoles.get(c.getId()),
                        c.getBackstory() != null ? c.getBackstory() : c.getShortDescription()))
                .toList();
    }

    private List<EmployeeSlot> slots(Savegame sg) {
        if (sg.getInitialEmployeesJson() == null) {
            return List.of();
        }
        return json.readValue(sg.getInitialEmployeesJson(), new TypeReference<List<EmployeeSlot>>() { });
    }

    public Savegame draft(Long id) {
        Savegame sg = savegames.findById(id).orElseThrow(() -> new NotFoundException("onboarding " + id));
        if (sg.getStatus() != SavegameStatus.DRAFT) {
            throw new BusinessRuleException("ALREADY_CONFIRMED", "Dieses Onboarding ist bereits abgeschlossen.");
        }
        return sg;
    }

    /** Reroll a single character (characterId) or the whole cast (null). */
    @Transactional
    public Savegame reroll(Long draftId, Long characterId) {
        Savegame sg = draft(draftId);
        List<EmployeeSlot> slots = new ArrayList<>(slots(sg));
        List<Character> targets = characterId == null ? characters.findBySavegameOrderByIdAsc(sg)
                : List.of(characters.findById(characterId).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("character " + characterId)));
        for (Character old : targets) {
            Optional<EmployeeSlot> slot = slots.stream().filter(s -> s.characterId().equals(old.getId())).findFirst();
            JobRole jr = slot.map(EmployeeSlot::jobRole).orElse(null);
            CharacterRole role = old.getRole();
            CharacterCategory category = old.getCategory();
            trustEvents.deleteByCharacter(old);
            characters.delete(old);
            characters.flush();
            Character fresh = generator.generate(sg, specFor(sg, role, category, jr), random.nextLong());
            enrich(fresh, sg);
            if (slot.isPresent()) {
                slots.remove(slot.get());
                slots.add(new EmployeeSlot(fresh.getId(), jr));
            }
        }
        sg.setInitialEmployeesJson(json.writeValueAsString(slots));
        return sg;
    }

    // ------------------------------------------------------------------------------------------ steps 3-5

    public List<DetectedSavegameRegistry.Detected> unlinkedSavegames() {
        return detected.list().stream().filter(d -> savegames.findByBridgeSavegameId(d.savegameId()).isEmpty()).toList();
    }

    @Transactional
    public Savegame confirm(Long draftId, String bridgeSavegameId) {
        Savegame sg = draft(draftId);
        if (savegames.findByBridgeSavegameId(bridgeSavegameId).isPresent()) {
            throw new BusinessRuleException("ALREADY_LINKED", "Dieser Spielstand ist bereits verknüpft.");
        }
        DetectedSavegameRegistry.Detected d = detected.get(bridgeSavegameId).orElseThrow(() -> new BusinessRuleException(
                "UNKNOWN_SAVEGAME", "Der Mod hat diesen Spielstand noch nicht gemeldet. Bitte den Spielstand in FS25 laden."));
        sg.setBridgeSavegameId(bridgeSavegameId);
        sg.setMapName(d.mapName());
        sg.setCurrentGameTime(d.gameTime());
        sg.setStatus(SavegameStatus.ACTIVE);
        sg.setLinkedAt(Instant.now());
        sg.setRotationYearIndex(0);
        // start employees: deterministic skill/salary like the applicant pool, no interview, neutral satisfaction
        for (EmployeeSlot slot : slots(sg)) {
            Character c = characters.findById(slot.characterId()).orElseThrow();
            HiringService.Offer offer = hiring.rollOffer(slot.jobRole(), RandomSource.seeded(c.getGenerationSeed()));
            hiring.createEmployee(sg, c, slot.jobRole(), offer.skill(), offer.salary());
        }
        // legacy loan: no scoring, no processing time, no CREDIT_DISBURSEMENT
        if (sg.getLegacyLoanAmount() != null && sg.getLegacyLoanAmount() > 0) {
            RpsimProperties.Credit c = props.getFormulas().getCredit();
            loans.create(sg, sg.getLegacyLoanAmount(), c.getLegacyInterestRate(), c.getLegacyTermMonths(),
                    "Altlast aus der Vorgeschichte", true, null);
        }
        scheduleStoryHooks(sg);
        diary.addAuto(sg, "BACKSTORY", "Vorgeschichte", backstoryText(sg), "SAVEGAME", sg.getId());
        narration.request(sg, NarrationEventType.ONBOARDING_WELCOME).from(lookup.bank(sg).orElse(null))
                .facts(NarrationFacts.builder().put("farmOrigin", sg.getFarmOrigin()).put("mapName", sg.getMapName())
                        .put("legacyLoan", sg.getLegacyLoanAmount() != null).build())
                .category(CommunicationCategory.ONBOARDING).submit();
        detected.remove(bridgeSavegameId);
        bridgeSync.resetCaches();
        return sg;
    }

    String backstoryText(Savegame sg) {
        String origin = switch (sg.getFarmOrigin()) {
            case INHERITED -> "Der Hof wurde geerbt.";
            case BOUGHT_FRESH_START -> "Der Hof wurde gekauft – ein Neustart.";
            case RETURNED_HOME -> "Rückkehr in die Heimat.";
            case LEASE_TAKEN_OVER -> "Eine bestehende Pacht wurde übernommen.";
        };
        String relation = switch (sg.getVillageRelation()) {
            case UNKNOWN -> "Im Dorf ist man noch unbekannt.";
            case CONNECTED -> "Man ist im Dorf gut vernetzt.";
            case STRAINED -> "Das Verhältnis zum Dorf ist belastet.";
        };
        String money = "Startkapital: " + sg.getStartingCapitalTarget() + " €"
                + (sg.getLegacyLoanAmount() != null ? ", Altlasten-Kredit: " + sg.getLegacyLoanAmount() + " €." : ".");
        String free = sg.getBackstoryFreeText() == null ? "" : "\n\n" + sg.getBackstoryFreeText();
        return origin + " " + relation + " " + money + free;
    }

    void scheduleStoryHooks(Savegame sg) {
        List<StoryHookCatalog.Hook> candidates = new ArrayList<>(StoryHookCatalog.candidates(sg.getFarmOrigin(),
                sg.getVillageRelation(), sg.getLegacyLoanAmount() != null));
        java.util.Collections.shuffle(candidates, new java.util.Random(sg.getGenerationSeed()));
        int count = Math.min(candidates.size(), random.intBetween(cfg().getStoryHooksMin(), cfg().getStoryHooksMax()));
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(sg.getCurrentGameTime() + GameTime.days(random.uniform(cfg().getStoryHookSpreadDaysMin(),
                    cfg().getStoryHookSpreadDaysMax())));
        }
        times.sort(Comparator.naturalOrder());
        for (int i = 0; i < count; i++) {
            StoryHookCatalog.Hook h = candidates.get(i);
            StoryHook hook = new StoryHook();
            hook.setSavegame(sg);
            hook.setHookKey(h.key());
            hook.setTitle(h.title());
            hook.setDescription(h.premise());
            hook.setCharacter(lookup.firstActive(sg, h.role(), CharacterRole.VILLAGER).orElse(null));
            hook.setScheduledGameTime(times.get(i));
            hooks.save(hook);
        }
    }

    /**
     * startingCapitalTarget: the real FS25 start capital is only known with the first farm_facts.json export; the
     * difference is queued once as STARTING_CAPITAL_ADJUSTMENT so the player lands exactly on the entered amount.
     */
    @EventListener
    @Order(1)
    @Transactional
    public void onFirstFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (sg.isStartingCapitalAdjusted()) {
            return;
        }
        sg.setStartingCapitalAdjusted(true);
        long diff = sg.getStartingCapitalTarget() - liquidity.latestBalance(sg);
        if (diff != 0) {
            outbox.money(sg, diff, MoneyReason.STARTING_CAPITAL_ADJUSTMENT, "Ausgleich Startkapital",
                    new Related("SAVEGAME", sg.getId()));
        }
    }
}
