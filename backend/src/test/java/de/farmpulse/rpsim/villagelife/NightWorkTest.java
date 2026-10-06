package de.farmpulse.rpsim.villagelife;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageNews;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.VillageNewsRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-D4: complaints about night work (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class NightWorkTest {

    static final long HOUR = GameTime.hours(1);
    static final String HARVESTABLE = """
            "fields": [{ "farmlandId": 12, "name": "Feld 12", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 8,
                         "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 9 }]""";

    @Autowired Fixtures fx;
    @Autowired NightWorkService nightWork;
    @Autowired NarrationJobRepository jobs;
    @Autowired VillageNewsRepository news;
    @Autowired JsonMapper json;

    Savegame sg;
    Character villager;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        villager = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Anna Albers");
        sg.setCurrentGameTime(GameTime.days(10) + 22 * HOUR); // 22:00
    }

    /** An export at the current game time; {@code jobs} running helpers. */
    private void export(boolean jobs, String extra) {
        long t = sg.getCurrentGameTime();
        String fields = """
                "calendar": { "period": 8, "dayInPeriod": 1, "daysPerPeriod": 1, "year": 1, "monotonicDay": %d,
                              "dayTimeMs": %d },
                "workforce": { "activeJobs": [%s] }""".formatted(GameTime.dayIndex(t), t % GameTime.days(1),
                jobs ? "{ \"jobId\": 1, \"title\": \"Pflügen\" }" : "");
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                fields + (extra == null ? "" : ", " + extra));
        FactsSnapshot s = fx.snapshot(sg, t, 100_000, doc);
        nightWork.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private void hours(int n, boolean jobs, String extra) {
        for (int i = 0; i < n; i++) {
            sg.setCurrentGameTime(sg.getCurrentGameTime() + HOUR);
            export(jobs, extra);
        }
    }

    private List<String> complaints() {
        return jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "NIGHT_WORK_COMPLAINT").stream()
                .map(j -> json.readTree(j.getFactsJson()).path("level").asString()).toList();
    }

    @Test
    void theNightWrapsAroundMidnight() {
        assertThat(NightWorkService.isNight(23 * HOUR, 22, 6)).isTrue();
        assertThat(NightWorkService.isNight(5 * HOUR, 22, 6)).isTrue();
        assertThat(NightWorkService.isNight(6 * HOUR, 22, 6)).isFalse();
        assertThat(NightWorkService.isNight(12 * HOUR, 22, 6)).isFalse();
    }

    @Test
    void threeNightHoursBringAFriendlyThenAnAnnoyedComplaint() {
        export(true, null); // the first export only starts the clock
        hours(2, true, null);
        assertThat(complaints()).isEmpty();
        hours(1, true, null);
        assertThat(complaints()).containsExactly("NIGHT_FRIENDLY");
        assertThat(villager.getTrustScore()).isEqualTo(-1);
        assertThat(news.findBySavegameOrderByIdAsc(sg)).isEmpty();

        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1) - 3 * HOUR); // the next evening, 22:00
        export(false, null);
        hours(3, true, null);
        assertThat(complaints()).containsExactly("NIGHT_FRIENDLY", "NIGHT_ANNOYED");
        assertThat(villager.getTrustScore()).isEqualTo(-4);
        assertThat(news.findBySavegameOrderByIdAsc(sg)).extracting(VillageNews::getKind).containsExactly("NIGHT_WORK");
    }

    @Test
    void daytimeHarvestAndIdleHelpersDoNotCount() {
        sg.setCurrentGameTime(GameTime.days(10) + 10 * HOUR);
        export(true, null);
        hours(4, true, null); // daytime
        sg.setCurrentGameTime(GameTime.days(10) + 22 * HOUR);
        export(true, HARVESTABLE);
        hours(4, true, HARVESTABLE); // harvest time
        hours(4, false, null); // no helper
        assertThat(nightWork.nightMs(sg, 0, sg.getCurrentGameTime())).isZero();
        assertThat(complaints()).isEmpty();
    }

    @Test
    void switchedOffItOnlyCountsAndIdyllicHalvesTheTrustLoss() {
        sg.setBurdenNightWork(false);
        export(true, null);
        hours(3, true, null);
        assertThat(complaints()).isEmpty();
        assertThat(nightWork.nightMs(sg, 0, sg.getCurrentGameTime())).isEqualTo(3 * HOUR); // for the farm holidays

        sg.setBurdenNightWork(true);
        sg.setTonePreset(TonePreset.IDYLLIC);
        nightWork.check(sg);
        assertThat(villager.getTrustScore()).isEqualTo(-0.5);
    }
}
