package de.farmpulse.rpsim.support;

import java.time.Instant;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.springframework.boot.test.context.TestComponent;

/** Creates common test fixtures (savegame, characters, snapshots) through the real repositories. */
@TestComponent
public class Fixtures {

    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final FactsSnapshotRepository snapshots;

    public Fixtures(SavegameRepository savegames, CharacterRepository characters, FactsSnapshotRepository snapshots) {
        this.savegames = savegames;
        this.characters = characters;
        this.snapshots = snapshots;
    }

    public Savegame savegame() {
        Savegame sg = TestData.activeSavegame("sg_" + System.nanoTime());
        sg.setCurrentGameTime(10 * 86_400_000L);
        return savegames.save(sg);
    }

    public Character character(Savegame sg, CharacterRole role, CharacterCategory category, String name) {
        return characters.save(TestCharacters.of(sg, role, category, name));
    }

    public Character bank(Savegame sg) {
        return character(sg, CharacterRole.BANK_ADVISOR, CharacterCategory.MANDATORY, "Frau Berger");
    }

    /** Snapshot with the standard farm (see TestData.farmFacts) at the savegame's current time. */
    public FactsSnapshot snapshot(Savegame sg, long balance) {
        return snapshot(sg, sg.getCurrentGameTime(), balance, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime(), balance));
    }

    public FactsSnapshot snapshot(Savegame sg, long gameTime, long balance, String json) {
        FactsSnapshot s = new FactsSnapshot();
        s.setSavegame(sg);
        s.setGameTime(gameTime);
        s.setBalance(balance);
        s.setReceivedAt(Instant.now());
        s.setRawJson(json);
        return snapshots.save(s);
    }
}
