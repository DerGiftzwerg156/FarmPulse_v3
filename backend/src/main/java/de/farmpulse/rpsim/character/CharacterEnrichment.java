package de.farmpulse.rpsim.character;

import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.TonePreset;

/**
 * Optional AI enrichment of the personality layer (backstory). Implemented by the AI adapter (Phase 5). The
 * generator works fully offline; if enrichment fails, the deterministic short description stays in place.
 * Enrichment NEVER touches fact-layer values.
 */
public interface CharacterEnrichment {

    /** @param freeText moderated onboarding free text (may be null/empty) */
    Optional<String> enrichBackstory(Character character, String freeText, TonePreset tone);
}
