package de.farmpulse.rpsim.character;

import de.farmpulse.rpsim.bridge.BridgeDtos.GameNpc;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-21: characters for the FS25 NPCs of the map (farmland owners). One character per NPC index and savegame,
 * named as in the game ({@code npc.title}); personality, trust and negotiation traits come from the regular generator.
 * They belong to the map and never move away (see VillageRotationService).
 */
@Service
public class GameNpcService {

    private final CharacterRepository characters;
    private final CharacterGeneratorService generator;
    private final VillageReputationService reputation;

    public GameNpcService(CharacterRepository characters, CharacterGeneratorService generator,
                          VillageReputationService reputation) {
        this.characters = characters;
        this.generator = generator;
        this.reputation = reputation;
    }

    public static boolean isGameNpc(Character c) {
        return c.getFs25NpcIndex() != null;
    }

    /** The character of the NPC, created on first use. Null when the export carries no usable NPC. */
    @Transactional
    public Character ensure(Savegame sg, GameNpc npc) {
        if (npc == null || npc.index() == null) {
            return null;
        }
        Character existing = characters.findFirstBySavegameAndFs25NpcIndex(sg, npc.index()).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != CharacterStatus.ACTIVE) {
                existing.setStatus(CharacterStatus.ACTIVE); // part of the map - comes back after a reset
                existing.setTerminationReason(null);
                existing.setLeftAtGameTime(null);
            }
            return existing;
        }
        // seed from the savegame and the NPC index: the same NPC gets the same personality on a re-import
        long seed = 31L * sg.getId() + npc.index();
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(CharacterRole.NEIGHBOR_FARMER,
                CharacterCategory.DYNAMIC, reputation.baseTrustForNewCharacter(sg)), seed);
        String title = npc.title() != null && !npc.title().isBlank() ? npc.title()
                : npc.name() != null && !npc.name().isBlank() ? npc.name() : c.getName();
        c.setName(title);
        c.setFs25NpcIndex(npc.index());
        c.setShortDescription(generator.shortDescription(c, null));
        c.setBackstory(c.getShortDescription());
        generator.enrich(c, null);
        return c;
    }
}
