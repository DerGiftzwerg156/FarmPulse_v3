package de.farmpulse.rpsim.dealer;

import java.util.Optional;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-H1: the agricultural machinery dealer ({@link CharacterRole#MACHINE_DEALER}). A new
 * savegame gets the dealer with its starting cast (OnboardingService.MANDATORY_ROLES); an older savegame gets the dealer
 * when the app "Händler" is opened for the first time - the dealer then introduces itself like every service character
 * ({@link ServiceRoleService#ensure}, mail and diary). Owner decision 2026-10-15.
 */
@Service
public class MachineDealerService {

    private final CharacterLookup lookup;
    private final ServiceRoleService roles;

    public MachineDealerService(CharacterLookup lookup, ServiceRoleService roles) {
        this.lookup = lookup;
        this.roles = roles;
    }

    /** The dealer of the savegame, if there is one (no side effect). */
    @Transactional(readOnly = true)
    public Optional<Character> dealer(Savegame sg) {
        return lookup.mandatory(sg, CharacterRole.MACHINE_DEALER);
    }

    /** First opening of the app: the dealer of the savegame, created and introduced when missing. */
    @Transactional
    public Character open(Savegame sg) {
        return roles.ensure(sg, CharacterRole.MACHINE_DEALER);
    }
}
