package de.farmpulse.rpsim.support;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.Savegame;

public final class TestCharacters {

    private TestCharacters() {
    }

    public static Character of(Savegame sg, CharacterRole role, CharacterCategory category, String name) {
        Character c = new Character();
        c.setSavegame(sg);
        c.setRole(role);
        c.setCategory(category);
        c.setStatus(CharacterStatus.ACTIVE);
        c.setName(name);
        c.setTraits("freundlich, direkt, bodenständig");
        c.setSpeechStyle("ruhig");
        c.setNegotiationTrait(NegotiationTrait.NEUTRAL);
        c.setVirtualWealth(200000);
        c.setGenerationSeed(name.hashCode());
        return c;
    }
}
