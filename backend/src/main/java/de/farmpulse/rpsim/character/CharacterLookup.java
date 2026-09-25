package de.farmpulse.rpsim.character;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import org.springframework.stereotype.Component;

/** Finds the responsible character for a role (mandatory roles are never vacant). */
@Component
public class CharacterLookup {

    private final CharacterRepository characters;

    public CharacterLookup(CharacterRepository characters) {
        this.characters = characters;
    }

    /**
     * The holder of a mandatory role (ACTIVE or ON_LEAVE - absence routing happens in the narration layer).
     * The fact file (loans etc.) belongs to the role/savegame, not to the person.
     */
    public Optional<Character> mandatory(Savegame sg, CharacterRole role) {
        return characters.findBySavegameAndCategoryInAndStatusIn(sg, List.of(CharacterCategory.MANDATORY),
                        List.of(CharacterStatus.ACTIVE, CharacterStatus.ON_LEAVE)).stream()
                .filter(c -> c.getRole() == role)
                .min(Comparator.comparing(Character::getId));
    }

    public Optional<Character> bank(Savegame sg) {
        return mandatory(sg, CharacterRole.BANK_ADVISOR);
    }

    /** First active character with one of the given roles (mandatory or dynamic), in preference order. */
    public Optional<Character> firstActive(Savegame sg, CharacterRole... roles) {
        List<Character> active = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE);
        for (CharacterRole r : roles) {
            Optional<Character> c = active.stream()
                    .filter(x -> x.getRole() == r && x.getCategory() != CharacterCategory.APPLICANT
                            && x.getCategory() != CharacterCategory.SUBSTITUTE)
                    .min(Comparator.comparing(Character::getId));
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    public List<Character> activeDynamic(Savegame sg) {
        return characters.findBySavegameAndCategoryAndStatus(sg, CharacterCategory.DYNAMIC, CharacterStatus.ACTIVE);
    }
}
