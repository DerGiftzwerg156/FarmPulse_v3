package de.farmpulse.rpsim.authority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.LivestockService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.AnimalDiseaseRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B4: animal disease and restricted zone (owner decisions 2026-10-05 in QUESTIONS.md).
 * <ul>
 *   <li>At a month start with probability-per-month (switch DISEASE, off in the idyllic world mode), at most one at a
 *   time and cooldown-months after the last lifting: a disease of animal-disease.diseases that hits an animal type the
 *   player keeps ({@code assets.animals}) breaks out; the restricted zone covers all its animal types for zone-months
 *   periods.</li>
 *   <li>Consequences: the authority mails, the cooperative reports, the village gossips; per affected stable a
 *   compulsory vet check (VET_INVOICE = (vet-base-fee + vet-fee-per-animal x animals) x vet-fee-factor) and a
 *   requirement (health from requirement-health within requirement-days, else requirement-fine). The livestock trade
 *   with the neighbours (A3) and the trader's offers are blocked for the types ({@link DiseaseZones}).</li>
 *   <li>After the lifting the prices of the neighbour trade recover ({@link DiseaseZones#priceFactor}); the game's
 *   animal prices stay untouched (roadmap "Bewusst nicht aufgenommen").</li>
 * </ul>
 */
@Service
public class AnimalDiseaseService {

    public static final String RELATED = "ANIMAL_DISEASE";
    public static final String RULE = "ANIMAL_DISEASE";

    private final SavegameRepository savegames;
    private final AnimalDiseaseRepository diseases;
    private final ServiceCaseRepository cases;
    private final FactsService facts;
    private final AuthorityService authority;
    private final BurdeningEvents burden;
    private final CharacterLookup lookup;
    private final ServiceRoleService roles;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final FallbackTemplates labels;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public AnimalDiseaseService(SavegameRepository savegames, AnimalDiseaseRepository diseases, ServiceCaseRepository cases,
                                FactsService facts, AuthorityService authority, BurdeningEvents burden,
                                CharacterLookup lookup, ServiceRoleService roles, OutboxService outbox,
                                NarrationRequestService narration, FallbackTemplates labels, TrustScoreService trust,
                                DiaryService diary, RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.diseases = diseases;
        this.cases = cases;
        this.facts = facts;
        this.authority = authority;
        this.burden = burden;
        this.lookup = lookup;
        this.roles = roles;
        this.outbox = outbox;
        this.narration = narration;
        this.labels = labels;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.AnimalDisease cfg() {
        return props.getFormulas().getAnimalDisease();
    }

    public List<AnimalDisease> list(Savegame sg) {
        return diseases.findBySavegameOrderByIdDesc(sg);
    }

    public Optional<AnimalDisease> active(Savegame sg) {
        return list(sg).stream().filter(d -> AnimalDisease.ACTIVE.equals(d.getStatus())).findFirst();
    }

    // ------------------------------------------------------------------------------------------ month start

    /** Before the vet and the trader (order 72): lift an ended zone, then maybe a new outbreak. */
    @EventListener
    @Order(70)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        active(sg).filter(d -> d.getEndsMonthIndex() <= month).ifPresent(d -> lift(sg, d));
        if (cfg().isEnabled() && burden.on(sg, Burden.DISEASE) && mayBreakOut(sg, month)
                && random.chance(cfg().getProbabilityPerMonth())) {
            breakOut(sg);
        }
    }

    boolean mayBreakOut(Savegame sg, long month) {
        if (active(sg).isPresent()) {
            return false;
        }
        return list(sg).stream().filter(d -> d.getLiftedMonthIndex() != null).findFirst()
                .map(d -> month - d.getLiftedMonthIndex() >= cfg().getCooldownMonths()).orElse(true);
    }

    /** A disease hitting an animal type the player keeps, chosen at random; empty without such a disease. */
    Optional<AnimalDisease> breakOut(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Set<String> own = LivestockService.herds(f).keySet();
        List<RpsimProperties.Disease> candidates = cfg().getDiseases().stream()
                .filter(d -> d.getAnimalTypes().stream().anyMatch(own::contains)).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(declare(sg, f, random.pick(candidates)));
    }

    @Transactional
    public AnimalDisease declare(Savegame sg, FarmFacts f, RpsimProperties.Disease disease) {
        long now = sg.getCurrentGameTime();
        long month = gameTime.monthIndex(sg, now);
        AnimalDisease d = new AnimalDisease();
        d.setSavegame(sg);
        d.setDiseaseKey(disease.getKey());
        d.setAnimalTypes(String.join(",", disease.getAnimalTypes()));
        d.setStatus(AnimalDisease.ACTIVE);
        d.setDeclaredGameTime(now);
        d.setDeclaredMonthIndex(month);
        d.setEndsMonthIndex(month + cfg().getZoneMonths());
        diseases.save(d);

        String types = disease.getAnimalTypes().stream().map(labels::label).collect(Collectors.joining(", "));
        Character amt = roles.ensure(sg, CharacterRole.AUTHORITY);
        narration.request(sg, NarrationEventType.ANIMAL_DISEASE_DECLARED).from(amt)
                .facts(NarrationFacts.builder().put("disease", disease.getKey()).put("animalTypes", types)
                        .put("zoneMonths", cfg().getZoneMonths()).put("requirementHealth", Math.round(cfg().getRequirementHealth()))
                        .put("requirementDays", Math.round(cfg().getRequirementDays())).build())
                .category(CommunicationCategory.LIVESTOCK).related(RELATED, d.getId()).formLink("/aemter").submit();
        lookup.mandatory(sg, CharacterRole.COOPERATIVE).ifPresent(c -> narration
                .request(sg, NarrationEventType.ANIMAL_DISEASE_NEWS).from(c)
                .facts(NarrationFacts.builder().put("disease", disease.getKey()).put("animalTypes", types)
                        .put("zoneMonths", cfg().getZoneMonths()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
        List<Character> dyn = lookup.activeDynamic(sg);
        Optional<Character> teller = dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER, CharacterRole.NEIGHBOR_FARMER)
                : Optional.of(random.pick(dyn));
        teller.ifPresent(t -> narration.request(sg, NarrationEventType.ANIMAL_DISEASE_GOSSIP).from(t)
                .facts(NarrationFacts.builder().put("disease", disease.getKey()).put("animalTypes", types).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
        diary.addAuto(sg, "LIVESTOCK", "Tierseuche: Sperrzone", labels.label(disease.getKey()) + " in der Region – Handel mit "
                + types + " für " + cfg().getZoneMonths() + " Monate gesperrt.", RELATED, d.getId());

        for (BridgeDtos.Animal a : stables(f, disease.getAnimalTypes())) {
            vetCheck(sg, a);
            authority.announce(sg, amt, RULE, a.husbandryUniqueId(), null, AuthorityService.stableLabel(f, a.husbandryUniqueId()),
                    cfg().getRequirementDays());
        }
        return d;
    }

    /** Own stables with animals of the types. */
    static List<BridgeDtos.Animal> stables(FarmFacts f, List<String> types) {
        List<BridgeDtos.Animal> out = new ArrayList<>();
        if (f == null || f.assets() == null || f.assets().animals() == null) {
            return out;
        }
        for (BridgeDtos.Animal a : f.assets().animals()) {
            if (a != null && a.husbandryUniqueId() != null && a.type() != null && types.contains(a.type())
                    && a.count() != null && a.count() > 0) {
                out.add(a);
            }
        }
        return out;
    }

    /** Compulsory check of a stable: (vet-base-fee + vet-fee-per-animal x animals) x vet-fee-factor as VET_INVOICE. */
    ServiceCase vetCheck(Savegame sg, BridgeDtos.Animal a) {
        RpsimProperties.Livestock vetCfg = props.getFormulas().getLivestock();
        long invoice = Math.round((vetCfg.getVetBaseFee() + vetCfg.getVetFeePerAnimal() * a.count()) * cfg().getVetFeeFactor());
        Character vet = roles.ensure(sg, CharacterRole.VETERINARIAN);
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.VET_VISIT);
        sc.setCharacter(vet);
        sc.setReference(a.type());
        sc.setTitle("Seuche " + a.husbandryUniqueId());
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("DISEASE_CHECK");
        sc.setCostAmount(invoice);
        sc.setQuantity(a.count());
        sc.setGameTime(now);
        sc.setClosedAtGameTime(now);
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        outbox.money(sg, -invoice, MoneyReason.VET_INVOICE, "Tierarzt Seuchen-Pflichtuntersuchung " + a.type(),
                new Related(LivestockService.RELATED, sc.getId()));
        narration.request(sg, NarrationEventType.ANIMAL_DISEASE_VET_CHECK).from(vet)
                .facts(NarrationFacts.builder().put("animalType", a.type()).put("animalCount", a.count()).put("invoice", invoice)
                        .build())
                .category(CommunicationCategory.LIVESTOCK).related(LivestockService.RELATED, sc.getId()).submit();
        diary.addAuto(sg, "LIVESTOCK", "Seuchen-Pflichtuntersuchung", a.count() + " Tiere (" + a.type() + ") untersucht, "
                + "Rechnung " + invoice + " €.", LivestockService.RELATED, sc.getId());
        return sc;
    }

    void lift(Savegame sg, AnimalDisease d) {
        long now = sg.getCurrentGameTime();
        d.setStatus(AnimalDisease.LIFTED);
        d.setLiftedGameTime(now);
        d.setLiftedMonthIndex(gameTime.monthIndex(sg, now));
        String types = d.types().stream().map(labels::label).collect(Collectors.joining(", "));
        narration.request(sg, NarrationEventType.ANIMAL_DISEASE_LIFTED).from(roles.ensure(sg, CharacterRole.AUTHORITY))
                .facts(NarrationFacts.builder().put("disease", d.getDiseaseKey()).put("animalTypes", types)
                        .put("recoveryMonths", cfg().getPriceRecoveryMonths()).build())
                .category(CommunicationCategory.LIVESTOCK).related(RELATED, d.getId()).submit();
        diary.addAuto(sg, "LIVESTOCK", "Sperrzone aufgehoben", labels.label(d.getDiseaseKey()) + ": Handel mit " + types
                + " wieder erlaubt.", RELATED, d.getId());
    }

    // ------------------------------------------------------------------------------------------ requirements

    /** Daily: requirements whose deadline passed - health from requirement-health or a fine. */
    @EventListener
    @Order(80)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        FarmFacts f = facts.latest(sg).orElse(null);
        Map<String, Double> health = new HashMap<>();
        if (f != null && f.husbandries() != null) {
            f.husbandries().stream().filter(h -> h != null && h.husbandryUniqueId() != null && h.health() != null)
                    .forEach(h -> health.put(h.husbandryUniqueId(), h.health()));
        }
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION))) {
            if (!RULE.equals(sc.getTitle()) || sc.getStatus() != CaseStatus.IN_PROGRESS || sc.getDeadlineGameTime() == null
                    || sc.getDeadlineGameTime() > now) {
                continue;
            }
            String label = AuthorityService.stableLabel(f, sc.getReference());
            Double h = health.get(sc.getReference());
            if (h == null || h >= cfg().getRequirementHealth()) {
                authority.resolve(sg, sc, "IN_ORDER", 0, label);
                continue;
            }
            long fine = Math.round(cfg().getRequirementFine() * burden.factor(sg));
            outbox.money(sg, -fine, MoneyReason.FINE, "Bußgeld Seuchen-Auflage", new Related(AuthorityService.RELATED, sc.getId()));
            trust.recordEvent(sc.getCharacter(), props.getFormulas().getAuthority().getViolationTrustDelta(),
                    TrustReason.AUTHORITY_VIOLATION, "Seuchen-Auflage " + label);
            sc.setCostAmount(fine);
            authority.resolve(sg, sc, "FINED", fine, label);
            diary.addAuto(sg, "OTHER", "Bußgeld vom Amt", fine + " € wegen der Seuchen-Auflage (" + label + ").",
                    AuthorityService.RELATED, sc.getId());
        }
    }
}
