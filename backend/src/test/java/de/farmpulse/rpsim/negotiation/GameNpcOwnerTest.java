package de.farmpulse.rpsim.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.bridge.BridgeDtos.GameNpc;
import de.farmpulse.rpsim.character.GameNpcService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.village.VillageRotationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-21: the FS25 NPCs of the farmlands become the owner characters. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class GameNpcOwnerTest {

    static final String CONTEXT = """
        { "savegameId": "%s", "mapName": "Erlengrund", "sellPoints": [], "fillTypes": [],
          "farmlands": [
            { "farmlandId": 13, "hectares": 6, "price": 72000, "ownerFarmId": 0,
              "npc": { "index": 3, "name": "npc_anna", "title": "Anna Berger" } },
            { "farmlandId": 14, "hectares": 8, "price": 100000, "ownerFarmId": 0,
              "npc": { "index": 3, "name": "npc_anna", "title": "Anna Berger" } },
            { "farmlandId": 15, "hectares": 2, "price": 20000, "ownerFarmId": 0 } ] }""";

    @Autowired Fixtures fx;
    @Autowired FarmlandOwnershipService ownership;
    @Autowired GameNpcService gameNpcs;
    @Autowired VillageRotationService rotation;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        props.getFormulas().getNegotiation().setNpcOwnedShare(1);
    }

    @AfterEach
    void restore() {
        var d = new RpsimProperties.Negotiation();
        props.getFormulas().getNegotiation().setNpcOwnedShare(d.getNpcOwnedShare());
        props.getFormulas().getNegotiation().setUseGameNpcOwners(d.isUseGameNpcOwners());
    }

    private FarmlandOwnership field(int id) {
        return ownership.get(sg, id).orElseThrow();
    }

    @Test
    void theNpcOfTheFarmlandOwnsItOneCharacterPerNpc() {
        ownership.reconcile(sg);
        Character anna = field(13).getOwnerCharacter();
        assertThat(field(13).getOwnerType()).isEqualTo(OwnerType.CHARACTER);
        assertThat(anna.getName()).isEqualTo("Anna Berger");
        assertThat(anna.getFs25NpcIndex()).isEqualTo(3);
        assertThat(anna.getCategory()).isEqualTo(CharacterCategory.DYNAMIC);
        assertThat(anna.getShortDescription()).startsWith("Anna Berger, ");
        assertThat(field(14).getOwnerCharacter()).isSameAs(anna);
        // no NPC in the export and no village character: the field stays on the market
        assertThat(field(15).getOwnerType()).isEqualTo(OwnerType.UNCLAIMED);
    }

    @Test
    void canBeSwitchedOffInTheConfiguration() {
        props.getFormulas().getNegotiation().setUseGameNpcOwners(false);
        ownership.reconcile(sg);
        assertThat(field(13).getOwnerType()).isEqualTo(OwnerType.UNCLAIMED);
    }

    @Test
    void gameNpcsNeverMoveAway() {
        Character anna = gameNpcs.ensure(sg, new GameNpc(3, "npc_anna", "Anna Berger"));
        assertThat(gameNpcs.ensure(sg, new GameNpc(3, "npc_anna", "Anna Berger"))).isSameAs(anna);
        for (int i = 0; i < 20; i++) {
            sg.setDynamicRotationsThisYear(0);
            rotation.rotate(sg);
        }
        assertThat(anna.getStatus()).isEqualTo(CharacterStatus.ACTIVE);
    }
}
