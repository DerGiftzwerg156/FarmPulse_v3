package de.farmpulse.rpsim.family;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-E3: family and succession. The family is chosen in the onboarding (owner decision: parents, partner and
 * children as independent switches); family members are characters of the role FAMILY with an affiliation.
 * <ul>
 *   <li>Retirement: the parents live on the farm's retirement share only when the farm was inherited or the player
 *   returned home (owner decision) - a monthly FAMILY payment.</li>
 *   <li>Occasions from the calendar: birthdays (occasion period per member), wedding day (partner), school start of
 *   the youngest child in the first year.</li>
 *   <li>Family wishes: the family field (marked by the player on the fields page) - selling it costs the trust of the
 *   whole family; help at harvest time (text and trust only).</li>
 *   <li>Succession: a yearly diary entry as long narrative arc.</li>
 * </ul>
 */
@Service
public class FamilyService {

    public static final String RELATED = "FAMILY";
    public static final String PARENT = "PARENT";
    public static final String PARENT_AWAY = "PARENT_AWAY";
    public static final String PARTNER = "PARTNER";
    public static final String CHILD = "CHILD";

    public record Choice(boolean parents, boolean partner, boolean children) {
        public static final Choice NONE = new Choice(false, false, false);
    }

    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final CharacterGeneratorService generator;
    private final FactsService facts;
    private final FarmlandOwnershipService ownership;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public FamilyService(SavegameRepository savegames, CharacterRepository characters, CharacterGeneratorService generator,
                         FactsService facts, FarmlandOwnershipService ownership, OutboxService outbox,
                         NarrationRequestService narration, TrustScoreService trust, DiaryService diary,
                         RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.characters = characters;
        this.generator = generator;
        this.facts = facts;
        this.ownership = ownership;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Family cfg() {
        return props.getFormulas().getFamily();
    }

    /** Retirement only for an inherited farm or a return home (owner decision). */
    public static boolean retirementOrigin(FarmOrigin origin) {
        return origin == FarmOrigin.INHERITED || origin == FarmOrigin.RETURNED_HOME;
    }

    // ------------------------------------------------------------------------------------------ onboarding

    /** Creates the family of a new savegame (onboarding, together with the backstory). */
    @Transactional
    public List<Character> create(Savegame sg, Choice choice) {
        sg.setFamilyParents(choice.parents());
        sg.setFamilyPartner(choice.partner());
        sg.setFamilyChildren(choice.children());
        boolean retirement = choice.parents() && retirementOrigin(sg.getFarmOrigin());
        sg.setRetirementPayment(retirement ? cfg().getRetirementPayment() : null);
        List<Character> family = new ArrayList<>();
        if (choice.parents()) {
            family.add(member(sg, retirement ? PARENT : PARENT_AWAY));
            family.add(member(sg, retirement ? PARENT : PARENT_AWAY));
        }
        if (choice.partner()) {
            family.add(member(sg, PARTNER));
        }
        if (choice.children()) {
            int n = random.intBetween(1, 2);
            for (int i = 0; i < n; i++) {
                family.add(member(sg, CHILD));
            }
        }
        // one family name: everybody except the partner carries the name of the first member
        String lastName = family.isEmpty() ? null : lastName(family.getFirst().getName());
        for (Character c : family) {
            if (lastName != null && !PARTNER.equals(c.getAffiliation())) {
                c.setName(c.getName().substring(0, c.getName().lastIndexOf(' ') + 1) + lastName);
                generator.affiliate(c, c.getAffiliation());
            }
        }
        return family;
    }

    /** Onboarding reroll of a family member: a new person with the same relation, the family name stays. */
    public Character reroll(Savegame sg, Character old) {
        Character c = member(sg, old.getAffiliation());
        if (!PARTNER.equals(old.getAffiliation())) {
            c.setName(c.getName().substring(0, c.getName().lastIndexOf(' ') + 1) + lastName(old.getName()));
            generator.affiliate(c, c.getAffiliation());
        }
        return c;
    }

    private Character member(Savegame sg, String affiliation) {
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(CharacterRole.FAMILY,
                CharacterCategory.MANDATORY, 0), random.nextLong());
        generator.affiliate(c, affiliation);
        c.setOccasionPeriod(random.intBetween(1, 12));
        return c;
    }

    static String lastName(String name) {
        int i = name.lastIndexOf(' ');
        return i < 0 ? name : name.substring(i + 1);
    }

    public List<Character> members(Savegame sg) {
        return characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .filter(c -> c.getRole() == CharacterRole.FAMILY).sorted(Comparator.comparing(Character::getId)).toList();
    }

    // ------------------------------------------------------------------------------------------ calendar

    @EventListener
    @Order(68)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled()) {
            return;
        }
        List<Character> family = members(sg);
        if (sg.getRetirementPayment() != null && sg.getRetirementPayment() > 0
                && family.stream().anyMatch(c -> PARENT.equals(c.getAffiliation()))) {
            outbox.money(sg, -sg.getRetirementPayment(), MoneyReason.FAMILY, "Altenteil", null);
        }
        if (family.isEmpty()) {
            return;
        }
        int period = gameTime.periodOfYear(sg, e.gameTime());
        occasions(sg, family, period);
        if (cfg().getHarvestPeriods().contains(period) && random.chance(cfg().getHarvestHelpProbability())) {
            harvestHelp(sg, family);
        }
        if (period == 1) {
            succession(sg, family);
        }
    }

    /** Birthdays, the wedding day (partner, half a year after the birthday period) and the school start. */
    void occasions(Savegame sg, List<Character> family, int period) {
        for (Character c : family) {
            if (c.getOccasionPeriod() != null && c.getOccasionPeriod() == period) {
                occasion(sg, c, "BIRTHDAY");
            }
            if (PARTNER.equals(c.getAffiliation()) && c.getOccasionPeriod() != null
                    && (c.getOccasionPeriod() + 5) % 12 + 1 == period) {
                occasion(sg, c, "WEDDING_DAY");
            }
        }
        Optional<Character> youngest = family.stream().filter(c -> CHILD.equals(c.getAffiliation()))
                .max(Comparator.comparing(Character::getId));
        if (youngest.isPresent() && period == cfg().getSchoolStartPeriod()
                && sg.getCurrentGameTime() - youngest.get().getJoinedAtGameTime() < 12 * gameTime.msPerMonth(sg)) {
            occasion(sg, youngest.get(), "SCHOOL_START");
        }
    }

    private void occasion(Savegame sg, Character c, String occasion) {
        narration.request(sg, NarrationEventType.FAMILY_OCCASION).from(c)
                .facts(NarrationFacts.builder().put("occasion", occasion).put("relation", c.getAffiliation()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, c.getId()).submit();
    }

    /** Help at harvest time: the partner or a child lends a hand (text and trust only, no game effect). */
    void harvestHelp(Savegame sg, List<Character> family) {
        List<Character> helpers = family.stream().filter(c -> PARTNER.equals(c.getAffiliation())
                || CHILD.equals(c.getAffiliation())).toList();
        if (helpers.isEmpty()) {
            return;
        }
        Character helper = random.pick(helpers);
        trust.recordEvent(helper, cfg().getHarvestHelpTrustDelta(), TrustReason.FAMILY_HELP, "Hilfe bei der Ernte");
        narration.request(sg, NarrationEventType.FAMILY_HARVEST_HELP).from(helper)
                .facts(NarrationFacts.builder().put("relation", helper.getAffiliation()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, helper.getId()).submit();
    }

    /** Yearly diary entry of the succession arc. */
    void succession(Savegame sg, List<Character> family) {
        long years = Math.max(1, (sg.getCurrentGameTime() - family.getFirst().getJoinedAtGameTime())
                / Math.max(1, 12 * gameTime.msPerMonth(sg)));
        boolean parents = family.stream().anyMatch(c -> c.getAffiliation() != null && c.getAffiliation().startsWith(PARENT));
        boolean children = family.stream().anyMatch(c -> CHILD.equals(c.getAffiliation()));
        StringBuilder text = new StringBuilder("Ein weiteres Jahr auf dem Hof. ");
        if (parents) {
            text.append(years < 3 ? "Die Eltern schauen genau hin, wie du den Betrieb führst. "
                    : "Die Eltern ziehen sich mehr und mehr zurück und überlassen dir die Entscheidungen. ");
        }
        if (children) {
            text.append(years < 3 ? "Die Kinder helfen schon beim Füttern und fragen viel über die Maschinen. "
                    : "Die Kinder fragen, ob der Hof einmal ihnen gehören wird – die Frage nach der Nachfolge wird lauter. ");
        }
        if (!parents && !children) {
            text.append("Wer den Hof einmal weiterführt, ist noch offen.");
        }
        diary.addAuto(sg, "OTHER", "Hofnachfolge – Jahr " + years, text.toString().strip(), null, null);
    }

    // ------------------------------------------------------------------------------------------ family field

    /** The player marks an own (not leased) field as family field; null clears the mark. */
    @Transactional
    public Savegame markFamilyField(Savegame sg, Integer farmlandId) {
        if (farmlandId != null) {
            FarmlandOwnership o = ownership.get(sg, farmlandId)
                    .orElseThrow(() -> new BusinessRuleException("UNKNOWN_FIELD", "Dieses Feld gibt es nicht."));
            if (o.getOwnerType() != OwnerType.PLAYER || o.isLeasedToPlayer()) {
                throw new BusinessRuleException("NOT_OWN_FIELD", "Nur ein eigenes Feld kann das Familienfeld sein.");
            }
        }
        sg.setFamilyFieldId(farmlandId);
        return sg;
    }

    /** Every export: the family field left the farm (sold in the tool or in the game menu) - the family reacts once. */
    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Integer field = sg.getFamilyFieldId();
        FarmFacts f = field == null ? null : facts.latest(sg).orElse(null);
        if (f == null || f.assets() == null || f.assets().farmland() == null) {
            return;
        }
        boolean owned = f.assets().farmland().stream().map(BridgeDtos.OwnedFarmland::farmlandId).anyMatch(field::equals);
        if (!owned) {
            familyFieldSold(sg, field);
        }
    }

    void familyFieldSold(Savegame sg, int farmlandId) {
        sg.setFamilyFieldId(null);
        List<Character> family = members(sg);
        diary.addAuto(sg, "OTHER", "Familienfeld verkauft", "Feld " + farmlandId + " war seit Generationen in der Familie "
                + "– jetzt gehört es nicht mehr zum Hof.", null, null);
        if (family.isEmpty() || !cfg().isEnabled()) {
            return;
        }
        for (Character c : family) {
            trust.recordEvent(c, cfg().getFieldSoldTrustDelta(), TrustReason.FAMILY_FIELD_SOLD, "Familienfeld verkauft");
        }
        Character speaker = family.stream().filter(c -> c.getAffiliation() != null && c.getAffiliation().startsWith(PARENT))
                .findFirst().orElse(family.getFirst());
        narration.request(sg, NarrationEventType.FAMILY_FIELD_SOLD).from(speaker)
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("relation", speaker.getAffiliation()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, speaker.getId()).submit();
    }
}
