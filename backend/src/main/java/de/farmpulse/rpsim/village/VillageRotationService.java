package de.farmpulse.rpsim.village;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterGeneratorService.Spec;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.character.GameNpcService;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arrival/departure of dynamic characters with a yearly budget cap (functional concept: at most 1-2 changes per
 * game year so the village feels familiar). Mandatory roles and employees are excluded.
 * The game year is the FS25 year of the calendar export (TODO T-08).
 */
@Service
public class VillageRotationService {

    public static final String RELATED = "CHARACTER";

    private final SavegameRepository savegames;
    private final CharacterLookup lookup;
    private final CharacterGeneratorService generator;
    private final VillageReputationService reputation;
    private final NarrationRequestService narration;
    private final FarmlandOwnershipService ownership;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public VillageRotationService(SavegameRepository savegames, CharacterLookup lookup, CharacterGeneratorService generator,
                                  VillageReputationService reputation, NarrationRequestService narration,
                                  FarmlandOwnershipService ownership, DiaryService diary, RandomSource random,
                                  RpsimProperties props, GameTime gameTime) {
        this.savegames = savegames;
        this.lookup = lookup;
        this.generator = generator;
        this.reputation = reputation;
        this.narration = narration;
        this.ownership = ownership;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Rotation cfg() {
        return props.getFormulas().getRotation();
    }

    @EventListener
    @Order(50)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        resetBudgetOnYearChange(sg);
        if (random.chance(cfg().getDailyProbability())) {
            rotate(sg);
        }
    }

    /**
     * Resets dynamicRotationsThisYear when the game year changes. T-08: the year is the FS25 year of the calendar
     * export; before the first calendar, 12 months of the fallback calendar form a year.
     */
    public void resetBudgetOnYearChange(Savegame sg) {
        int year = sg.getCalYear() != null ? sg.getCalYear()
                : (int) Math.floorDiv(gameTime.monthIndex(sg, sg.getCurrentGameTime()), GameTime.PERIODS_PER_YEAR);
        if (year != sg.getRotationYearIndex()) {
            sg.setRotationYearIndex(year);
            sg.setDynamicRotationsThisYear(0);
        }
    }

    public boolean budgetLeft(Savegame sg) {
        return sg.getDynamicRotationsThisYear() < cfg().getMaxPerYear();
    }

    /** One rotation step if budget is left: departure or arrival (both count against the same budget). */
    @Transactional
    public Optional<Character> rotate(Savegame sg) {
        if (!budgetLeft(sg)) {
            return Optional.empty();
        }
        // T-21: FS25 NPCs of the map (field owners) never move away and do not count against the rotation limits
        List<Character> dynamic = lookup.activeDynamic(sg).stream().filter(c -> !GameNpcService.isGameNpc(c)).toList();
        boolean depart = dynamic.size() > cfg().getMinDynamicCharacters()
                && (dynamic.size() >= cfg().getMaxDynamicCharacters() || random.chance(cfg().getMoveAwayShare()));
        Optional<Character> changed = depart ? Optional.of(depart(sg, random.pick(dynamic))) : Optional.of(arrive(sg));
        sg.setDynamicRotationsThisYear(sg.getDynamicRotationsThisYear() + 1);
        return changed;
    }

    @Transactional
    public Character depart(Savegame sg, Character c) {
        c.setStatus(CharacterStatus.TERMINATED);
        c.setTerminationReason(random.chance(cfg().getRetirementShare()) ? TerminationReason.RETIREMENT
                : TerminationReason.MOVED_AWAY);
        c.setLeftAtGameTime(sg.getCurrentGameTime());
        // fields of a departing NPC go back to the market
        for (FarmlandOwnership o : ownership.ownedBy(c)) {
            ownership.setOwner(sg, o.getFarmlandId(), OwnerType.UNCLAIMED, null);
        }
        narration.request(sg, NarrationEventType.CHARACTER_FAREWELL).from(c)
                .facts(NarrationFacts.builder().put("reason", c.getTerminationReason()).build())
                .category(CommunicationCategory.ROTATION).related(RELATED, c.getId()).submit();
        diary.addAuto(sg, "ROTATION", c.getName() + " verlässt das Dorf",
                c.getTerminationReason() == TerminationReason.RETIREMENT ? c.getName() + " geht in den Ruhestand."
                        : c.getName() + " zieht weg.", RELATED, c.getId());
        return c;
    }

    /**
     * Role change of a mandatory role (e.g. a new bank advisor): the fact file (loans, payment history) belongs to
     * the role/savegame and stays; the personal relationship restarts with the reputation-based start trust.
     */
    @Transactional
    public Character replaceMandatory(Savegame sg, Character old) {
        old.setStatus(CharacterStatus.TERMINATED);
        old.setTerminationReason(TerminationReason.RETIREMENT);
        old.setLeftAtGameTime(sg.getCurrentGameTime());
        Character successor = generator.generate(sg, Spec.of(old.getRole(), CharacterCategory.MANDATORY,
                reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        narration.request(sg, NarrationEventType.CHARACTER_INTRODUCTION).from(successor)
                .facts(NarrationFacts.builder().put("role", old.getRole()).put("predecessorName", old.getName()).build())
                .category(CommunicationCategory.ROTATION).related(RELATED, successor.getId()).submit();
        diary.addAuto(sg, "ROTATION", successor.getName() + " übernimmt", successor.getName() + " folgt auf "
                + old.getName() + ".", RELATED, successor.getId());
        return successor;
    }

    @Transactional
    public Character arrive(Savegame sg) {
        CharacterRole role = random.pick(List.of(CharacterRole.NEIGHBOR_FARMER, CharacterRole.VILLAGER, CharacterRole.SUPPLIER));
        // the player's reputation precedes them: same baseTrustForNewCharacter formula as in the onboarding
        Character c = generator.generate(sg, Spec.of(role, CharacterCategory.DYNAMIC,
                reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        generator.enrich(c, null);
        narration.request(sg, NarrationEventType.CHARACTER_INTRODUCTION).from(c)
                .facts(NarrationFacts.builder().put("role", role).build())
                .category(CommunicationCategory.ROTATION).related(RELATED, c.getId()).submit();
        diary.addAuto(sg, "ROTATION", c.getName() + " zieht ins Dorf", c.getShortDescription(), RELATED, c.getId());
        return c;
    }
}
