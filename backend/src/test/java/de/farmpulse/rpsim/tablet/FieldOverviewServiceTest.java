package de.farmpulse.rpsim.tablet;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.api.Views.FieldOverviewView;
import de.farmpulse.rpsim.api.Views.FieldRowView;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldDocs;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Hof-Tablet "Flurkarte": field rows with what needs doing and the rotation premium preview of the running year. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldOverviewServiceTest {

    @Autowired Fixtures fx;
    @Autowired FieldService fields;
    @Autowired FieldOverviewService overview;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
    }

    private void fieldsAt(double days, int year, String... docs) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.days(days);
        sg.setCurrentGameTime(t);
        var s = fx.snapshot(sg, t, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), t, year, FieldDocs.ALL_RULES, null, docs));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private static FieldRowView row(FieldOverviewView v, int farmlandId) {
        return v.fields().stream().filter(r -> r.farmlandId() == farmlandId).findFirst().orElseThrow();
    }

    @Test
    void rotationOfTheRunningYearAndThePremiumItWouldBring() {
        fieldsAt(0, 1, FieldDocs.field(12, "WHEAT", 8, false, false, 0, 0, 1, 1), FieldDocs.field(13, "WHEAT", 8, false, false, 0, 0, 1, 1));
        fieldsAt(1, 2, FieldDocs.field(12, "WHEAT", 3, false, false, 0, 0, 1, 1), FieldDocs.field(13, "BARLEY", 3, false, false, 0, 0, 1, 1));
        FieldOverviewView v = overview.overview(sg);
        assertThat(v.year()).isEqualTo(2);
        assertThat(v.tracked()).isTrue();
        assertThat(row(v, 12).rotation()).isEqualTo("SAME");
        assertThat(row(v, 12).previousCrop()).isEqualTo("WHEAT");
        assertThat(row(v, 13).rotation()).isEqualTo("CHANGED");
        assertThat(v.rotation().sameFields()).containsExactly(12);
        assertThat(v.rotation().cut()).as("first repeat: notice only").isFalse();
        assertThat(v.rotation().premium()).isEqualTo(Math.round(row(v, 13).hectares() * 40));
    }

    @Test
    void needsLimeAndPlowOnlyOnABareFieldWhenTheSavegameRequiresIt() {
        // weeds 8, stones 3, lime 0, plow 0 on an empty field
        fieldsAt(0, 1, FieldDocs.field(12, null, 0, false, false, 8, 3, 0, 0), FieldDocs.field(13, "WHEAT", 3, false, false, 0, 0, 0, 0));
        FieldOverviewView v = overview.overview(sg);
        FieldRowView bare = row(v, 12);
        assertThat(bare.needsLime()).isTrue();
        assertThat(bare.needsPlow()).isTrue();
        assertThat(bare.weedsHigh()).isTrue();
        assertThat(bare.stonesHigh()).isTrue();
        assertThat(bare.rotation()).isEqualTo("UNKNOWN");
        FieldRowView growing = row(v, 13);
        assertThat(growing.needsLime()).as("a growing crop is not limed").isFalse();
        assertThat(growing.needsPlow()).isFalse();
    }
}
