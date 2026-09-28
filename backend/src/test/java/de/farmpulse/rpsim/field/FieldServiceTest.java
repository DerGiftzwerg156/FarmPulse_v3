package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V2 R2-C1 / R2-C2: growth phase, field records, crop history and rain hours. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldServiceTest {

    @Autowired Fixtures fx;
    @Autowired FieldService fields;
    @Autowired NarrationJobRepository jobs;
    @Autowired GameTime gameTime;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
    }

    private void ingest(long gameTime, int year, Boolean raining, String... fieldDocs) {
        sg.setCurrentGameTime(gameTime);
        var s = fx.snapshot(sg, gameTime, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), gameTime, year,
                FieldDocs.ALL_RULES, raining, fieldDocs));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), gameTime, false));
    }

    private static BridgeDtos.Field f(String crop, int gs, Boolean withered, Boolean cut) {
        return new BridgeDtos.Field(1, "1", 1.0, crop, gs, crop == null ? null : 8, crop == null ? null : 8, 0, 0, 0, 0, 0,
                null, withered, cut, null, null);
    }

    @Test
    void phaseFollowsTheGameFlagsAndTheRoadmapRuleWithoutThem() {
        assertThat(FieldService.phase(f(null, 0, null, null))).isEqualTo(FieldPhase.EMPTY);
        assertThat(FieldService.phase(f("WHEAT", 4, false, false))).isEqualTo(FieldPhase.GROWING);
        assertThat(FieldService.phase(f("WHEAT", 8, false, false))).isEqualTo(FieldPhase.HARVESTABLE);
        assertThat(FieldService.phase(f("WHEAT", 9, false, true))).as("stubble after the harvest").isEqualTo(FieldPhase.HARVESTED);
        assertThat(FieldService.phase(f("WHEAT", 10, true, false))).isEqualTo(FieldPhase.WITHERED);
        assertThat(FieldService.phase(f("WHEAT", 9, null, null))).as("older mod: above max = withered")
                .isEqualTo(FieldPhase.WITHERED);
        assertThat(FieldService.progress(f("WHEAT", 4, false, false))).isCloseTo(0.5, within(1e-9));
        assertThat(FieldService.progress(f("WHEAT", 8, false, false))).isEqualTo(1.0);
        assertThat(FieldService.progress(f("WHEAT", 9, false, true))).isZero();
    }

    @Test
    void recordsFollowThePhaseAndTheHistoryKnowsTheHarvest() {
        long t = sg.getCurrentGameTime();
        ingest(t, 1, null, FieldDocs.growing("WHEAT", 4));
        FieldRecord r = fields.records(sg).getFirst();
        assertThat(r.getPhase()).isEqualTo(FieldPhase.GROWING);
        assertThat(r.getFieldName()).isEqualTo("12");
        assertThat(sg.isFieldsTracked()).isTrue();
        ingest(t + GameTime.hours(2), 1, null, FieldDocs.growing("WHEAT", 8));
        ingest(t + GameTime.hours(4), 1, null, FieldDocs.field(12, "WHEAT", 9, false, true, 0, 0, 1, 1));
        List<FieldCropHistory> crops = fields.cropsOfYear(sg, 1);
        assertThat(crops).singleElement().satisfies(h -> {
            assertThat(h.getFruitType()).isEqualTo("WHEAT");
            assertThat(h.isHarvestableSeen()).isTrue();
            assertThat(h.isHarvested()).isTrue();
            assertThat(h.isWithered()).isFalse();
        });
        assertThat(fields.records(sg).getFirst().getPhase()).isEqualTo(FieldPhase.HARVESTED);
    }

    @Test
    void aNewYearClosesTheHarvestYearAndTheCooperativeCongratulates() {
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Herr Brandt");
        long t = sg.getCurrentGameTime();
        ingest(t, 1, null, FieldDocs.growing("WHEAT", 8));
        ingest(t + GameTime.hours(1), 1, null, FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1));
        assertThat(fields.cropsOfYear(sg, 1).getFirst().isHarvested()).as("crop gone after harvestable = harvested")
                .isTrue();
        ingest(t + GameTime.days(1), 2, null, FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1));
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType)
                .containsExactly("FIELD_HARVEST_CONGRATULATION");
    }

    @Test
    void aWitheredCropSpoilsTheCongratulation() {
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Herr Brandt");
        long t = sg.getCurrentGameTime();
        ingest(t, 1, null, FieldDocs.growing("WHEAT", 8), FieldDocs.field(13, "BARLEY", 8, false, false, 0, 0, 1, 1));
        ingest(t + GameTime.hours(1), 1, null, FieldDocs.field(12, "WHEAT", 9, false, true, 0, 0, 1, 1),
                FieldDocs.field(13, "BARLEY", 10, true, false, 0, 0, 1, 1));
        ingest(t + GameTime.days(1), 2, null, FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1));
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).isEmpty();
        assertThat(fields.records(sg)).extracting(FieldRecord::getFarmlandId).as("sold field 13 is dropped")
                .containsExactly(12);
    }

    @Test
    void rainHoursAreSampledAndHeld() {
        long t = sg.getCurrentGameTime();
        long month = gameTime.monthIndex(sg, t);
        ingest(t, 1, true);
        ingest(t + GameTime.hours(1), 1, false); // raining for the last hour
        ingest(t + GameTime.hours(3), 1, false); // two dry hours
        assertThat(fields.rainShare(sg, month)).isCloseTo(1 / 3.0, within(1e-9));
        ingest(t + GameTime.hours(2), 1, true); // reload without saving: only the reference point moves
        ingest(t + GameTime.hours(12), 1, true); // gap of 10 h > 3 h: not counted
        assertThat(fields.rainPeriod(sg, month).orElseThrow().getObservedMs()).isEqualTo(GameTime.hours(3));
        sg.setFieldsTracked(true);
        ingest(t + GameTime.hours(13), 1, null, (String[]) null);
        assertThat(sg.isFieldsTracked()).as("no field block = older mod").isFalse();
    }
}
