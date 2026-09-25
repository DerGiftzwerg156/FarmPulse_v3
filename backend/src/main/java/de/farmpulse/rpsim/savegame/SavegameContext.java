package de.farmpulse.rpsim.savegame;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.springframework.stereotype.Component;

/**
 * The active savegame context. V1 runs without auth on localhost and the savegameId is implicit: it is the
 * linked savegame whose id the mod currently exports. Kept as an explicit service-level property so that a
 * later LAN mode (with password) does not require restructuring.
 */
@Component
public class SavegameContext {

    private final SavegameRepository savegames;
    private final AtomicReference<String> currentBridgeId = new AtomicReference<>();

    public SavegameContext(SavegameRepository savegames) {
        this.savegames = savegames;
    }

    public void setCurrentBridgeSavegameId(String bridgeId) {
        currentBridgeId.set(bridgeId);
    }

    public String currentBridgeSavegameId() {
        return currentBridgeId.get();
    }

    public Optional<Savegame> findActive() {
        String id = currentBridgeId.get();
        if (id != null) {
            Optional<Savegame> s = savegames.findByBridgeSavegameId(id).filter(sg -> sg.getStatus() == SavegameStatus.ACTIVE);
            if (s.isPresent()) {
                return s;
            }
        }
        return savegames.findFirstByStatusOrderByLinkedAtDesc(SavegameStatus.ACTIVE);
    }

    public Savegame requireActive() {
        return findActive().orElseThrow(() -> new BusinessRuleException("NO_ACTIVE_SAVEGAME",
                "Kein verknüpfter Spielstand aktiv. Bitte zuerst das Onboarding abschließen."));
    }
}
