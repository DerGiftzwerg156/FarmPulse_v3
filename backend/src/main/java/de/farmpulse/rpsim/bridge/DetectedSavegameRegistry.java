package de.farmpulse.rpsim.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * savegameIds reported by the mod that are not linked yet (onboarding step 3/4: the web app lists them with
 * map name and time for confirmation).
 */
@Component
public class DetectedSavegameRegistry {

    public record Detected(String savegameId, String mapName, Instant firstSeen, Instant lastSeen, long gameTime,
                           long balance) {
    }

    private final Map<String, Detected> detected = new ConcurrentHashMap<>();
    /** Map names from market_context.json (may arrive before the first farm_facts.json). */
    private final Map<String, String> mapNames = new ConcurrentHashMap<>();

    public void report(String savegameId, String reportedMapName, long gameTime, long balance) {
        String mapName = reportedMapName != null ? reportedMapName : mapNames.get(savegameId);
        detected.merge(savegameId, new Detected(savegameId, mapName, Instant.now(), Instant.now(), gameTime, balance),
                (old, now) -> new Detected(savegameId, mapName != null ? mapName : old.mapName(), old.firstSeen(),
                        now.lastSeen(), gameTime, balance));
    }

    public void updateMapName(String savegameId, String mapName) {
        if (mapName != null) {
            mapNames.put(savegameId, mapName);
        }
        detected.computeIfPresent(savegameId, (k, d) -> new Detected(k, mapName, d.firstSeen(), d.lastSeen(),
                d.gameTime(), d.balance()));
    }

    public List<Detected> list() {
        List<Detected> l = new ArrayList<>(detected.values());
        l.sort(Comparator.comparing(Detected::lastSeen).reversed());
        return l;
    }

    public java.util.Optional<Detected> get(String savegameId) {
        return java.util.Optional.ofNullable(detected.get(savegameId));
    }

    public void remove(String savegameId) {
        detected.remove(savegameId);
    }

    public void clear() {
        detected.clear();
        mapNames.clear();
    }
}
