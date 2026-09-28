package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-C4 / R2-C6: neighbor, gossip and hints from the real fields. Without a calendar anchor a game month is
 * one game day (FS25 default "days per period").
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldReactionServiceTest {

    @Autowired Fixtures fx;
    @Autowired FieldService fields;
    @Autowired FieldReactionService reactions;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;

    Savegame sg;
    Character neighbor;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        neighbor = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Otto Wendler");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Herr Brandt");
    }

    /** Ingests the fields at the given day offset and runs the daily reactions. */
    private void day(double days, String rules, String... fieldDocs) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.days(days);
        sg.setCurrentGameTime(t);
        var s = fx.snapshot(sg, t, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), t, 1, rules, null, fieldDocs));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        reactions.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(t), t));
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<String> facts(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals(type))
                .map(NarrationJob::getFactsJson).toList();
    }

    private static String weedy(int weeds) {
        return FieldDocs.field(12, "WHEAT", 4, false, false, weeds, 0, 1, 1);
    }

    @Test
    void theNeighborAsksFriendlyFirstAndGetsAnnoyedWithATrustLoss() {
        day(0, FieldDocs.ALL_RULES, weedy(6));
        day(1, FieldDocs.ALL_RULES, weedy(6));
        assertThat(messages()).as("weeds only for one month").isEmpty();
        day(2, FieldDocs.ALL_RULES, weedy(6));
        assertThat(facts("FIELD_NEIGHBOR_COMPLAINT")).singleElement().asString()
                .contains("\"topic\":\"WEEDS\"", "\"stage\":\"NEIGHBOR_FRIENDLY\"");
        day(3, FieldDocs.ALL_RULES, weedy(6));
        assertThat(facts("FIELD_NEIGHBOR_COMPLAINT")).hasSize(2).last().asString().contains("NEIGHBOR_ANNOYED");
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(neighbor))
                .anyMatch(e -> e.getReason() == TrustReason.FIELD_NEGLECTED && e.getDelta() < 0);
        day(8, FieldDocs.ALL_RULES, weedy(6));
        assertThat(facts("FIELD_NEIGHBOR_COMPLAINT")).as("nothing more in this episode").hasSize(2);
        day(9, FieldDocs.ALL_RULES, weedy(0));
        day(10, FieldDocs.ALL_RULES, weedy(6));
        day(12, FieldDocs.ALL_RULES, weedy(6));
        assertThat(facts("FIELD_NEIGHBOR_COMPLAINT")).as("a new episode starts friendly again").hasSize(3);
    }

    @Test
    void weedsDoNotCountWhenTheSavegameHasThemSwitchedOff() {
        String noWeeds = FieldDocs.ALL_RULES.replace("\"weedsEnabled\": true", "\"weedsEnabled\": false");
        day(0, noWeeds, weedy(9));
        day(5, noWeeds, weedy(9));
        day(0, null, weedy(9)); // without fieldRules nothing is known either
        assertThat(facts("FIELD_NEIGHBOR_COMPLAINT")).isEmpty();
    }

    @Test
    void theVillageGossipsAboutAWitheredCropOnceAndAboutALongFallowField() {
        day(0, FieldDocs.ALL_RULES, FieldDocs.field(12, "WHEAT", 10, true, false, 0, 0, 1, 1));
        day(1, FieldDocs.ALL_RULES, FieldDocs.field(12, "WHEAT", 10, true, false, 0, 0, 1, 1));
        assertThat(facts("FIELD_GOSSIP")).singleElement().asString().contains("\"topic\":\"WITHERED\"");
        String bare = FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1);
        day(2, FieldDocs.ALL_RULES, bare);
        day(5, FieldDocs.ALL_RULES, bare);
        assertThat(facts("FIELD_GOSSIP")).hasSize(1);
        day(6, FieldDocs.ALL_RULES, bare);
        assertThat(facts("FIELD_GOSSIP")).hasSize(2).last().asString().contains("\"topic\":\"FALLOW\"");
        day(7, FieldDocs.ALL_RULES, bare);
        assertThat(facts("FIELD_GOSSIP")).hasSize(2);
    }

    @Test
    void theCooperativeGivesOneHintPerWeekHarvestFirstAndOnlyForRequiredWork() {
        String ripe = FieldDocs.field(12, "WHEAT", 8, false, false, 0, 0, 1, 1);
        String bareNoLimeNoPlow = FieldDocs.field(13, null, 0, null, null, 0, 0, 0, 0);
        day(0, FieldDocs.ALL_RULES, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).singleElement().asString().contains("\"hint\":\"HARVEST_READY\"");
        day(3, FieldDocs.ALL_RULES, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).as("cooldown").hasSize(1);
        day(7, FieldDocs.ALL_RULES, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).hasSize(2).last().asString().contains("\"hint\":\"LIME\"");
        String noPlowing = FieldDocs.ALL_RULES.replace("\"plowingRequired\": true", "\"plowingRequired\": false");
        day(14, noPlowing, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).as("plowing is not required in this savegame").hasSize(2);
        sg.setFieldHintsEnabled(false);
        day(21, FieldDocs.ALL_RULES, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).as("switched off").hasSize(2);
        sg.setFieldHintsEnabled(true);
        day(28, FieldDocs.ALL_RULES, ripe, bareNoLimeNoPlow);
        assertThat(facts("FIELD_WORK_HINT")).hasSize(3).last().asString().contains("\"hint\":\"PLOW\"");
    }

    @Test
    void fieldMessagesAreCappedPerMonth() {
        String w12 = FieldDocs.field(12, "WHEAT", 4, false, false, 6, 0, 1, 1);
        String w13 = FieldDocs.field(13, "WHEAT", 4, false, false, 6, 0, 1, 1);
        String w14 = FieldDocs.field(14, "WHEAT", 10, true, false, 6, 0, 1, 1);
        sg.setFieldHintsEnabled(false);
        day(0, FieldDocs.ALL_RULES, w12, w13);
        day(2, FieldDocs.ALL_RULES, w12, w13, w14); // two neighbor messages and the gossip about field 14 are due
        assertThat(messages()).as("at most 2 per game month").containsExactly("FIELD_NEIGHBOR_COMPLAINT",
                "FIELD_NEIGHBOR_COMPLAINT");
        day(3, FieldDocs.ALL_RULES, weedy(0), w14); // weeds gone, next month: the gossip follows
        assertThat(messages()).hasSize(3).last().isEqualTo("FIELD_GOSSIP");
    }
}
