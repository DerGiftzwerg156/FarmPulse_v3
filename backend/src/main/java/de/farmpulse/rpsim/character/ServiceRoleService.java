package de.farmpulse.rpsim.character;

import java.util.Optional;

import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-20 / T-22: the service characters (insurance agent, hunter, vet, livestock trader, breeding advisor, energy
 * supplier, workshop, contractor) are mandatory roles that appear when their first occasion arises - also in
 * savegames created before they existed. They introduce themselves like a new villager.
 */
@Service
public class ServiceRoleService {

    public static final String RELATED = "CHARACTER";

    private final CharacterLookup lookup;
    private final CharacterGeneratorService generator;
    private final VillageReputationService reputation;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;

    public ServiceRoleService(CharacterLookup lookup, CharacterGeneratorService generator, VillageReputationService reputation,
                              NarrationRequestService narration, DiaryService diary, RandomSource random) {
        this.lookup = lookup;
        this.generator = generator;
        this.reputation = reputation;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
    }

    /** The holder of the role, created (and introduced) if the savegame does not have one yet. */
    @Transactional
    public Character ensure(Savegame sg, CharacterRole role) {
        Optional<Character> existing = lookup.mandatory(sg, role);
        if (existing.isPresent()) {
            return existing.get();
        }
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(role, CharacterCategory.MANDATORY,
                reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        generator.enrich(c, null);
        narration.request(sg, NarrationEventType.CHARACTER_INTRODUCTION).from(c)
                .facts(NarrationFacts.builder().put("role", role).build())
                .category(CommunicationCategory.ROTATION).related(RELATED, c.getId()).submit();
        diary.addAuto(sg, "ROTATION", c.getName() + " stellt sich vor", c.getShortDescription(), RELATED, c.getId());
        return c;
    }
}
