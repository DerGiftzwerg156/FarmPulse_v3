package de.farmpulse.rpsim.villagelife;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmHolidayMonth;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NightWorkSample;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.NightWorkSampleRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-D6: farm holidays and school visits (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FarmHolidaySchoolTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired FarmHolidayService holidays;
    @Autowired SchoolVisitService school;
    @Autowired ContractActions actions;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NightWorkSampleRepository nightSamples;
    @Autowired NarrationJobRepository jobs;
    @Autowired ServiceCaseRepository cases;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(5); // July
        stable(85);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setSchoolVisit(new RpsimProperties.SchoolVisit());
    }

    private void stable(double health) {
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime(), 100_000), "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": "
                + health + " }]"));
    }

    private JsonNode lastMoney() {
        return json.readTree(outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION).getLast()
                .getPayloadJson());
    }

    private void nextMonth() {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
        holidays.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    @Test
    void setupOnceThenGuestsPayPerFullMonth() {
        holidays.setup(sg);
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(-20_000);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("FARM_HOLIDAY_SETUP");
        assertThatThrownBy(() -> holidays.setup(sg)).isInstanceOf(BusinessRuleException.class);

        nextMonth(); // July: 800 x 1.5 (summer) x 1.0 (neutral) x 1.2 (healthy stable)
        List<FarmHolidayMonth> months = holidays.history(sg);
        assertThat(months).hasSize(1);
        assertThat(months.getFirst().getIncome()).isEqualTo(1440);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("GUEST_INCOME");
        assertThat(jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "FARM_HOLIDAY_REVIEW")).isEmpty();

        // August with night work and slurry: -10 % each and a review of the guests
        NightWorkSample s = new NightWorkSample();
        s.setSavegame(sg);
        s.setGameTime(sg.getCurrentGameTime() + DAY / 2);
        s.setNightMs(GameTime.hours(1));
        nightSamples.save(s);
        sg.setLastOrganicSpreadGameTime(sg.getCurrentGameTime() + DAY / 2);
        nextMonth();
        assertThat(holidays.history(sg).getFirst().getIncome()).isEqualTo(Math.round(800 * 1.5 * 1.2 * 0.8));
        assertThat(holidays.history(sg).getFirst().isNoise()).isTrue();
        assertThat(holidays.history(sg).getFirst().isSmell()).isTrue();
        assertThat(jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "FARM_HOLIDAY_REVIEW")).hasSize(1);
    }

    @Test
    void offSeasonAndSickAnimalsLowerTheIncome() {
        sg.setCalPeriod(8); // October
        stable(30);
        holidays.setup(sg);
        nextMonth();
        FarmHolidayMonth m = holidays.history(sg).getFirst();
        assertThat(m.getIncome()).isEqualTo(Math.round(800 * 0.7 * 0.6));
        assertThat(m.isBadReview()).isTrue();
        assertThat(jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "FARM_HOLIDAY_REVIEW")).hasSize(1);
    }

    @Test
    void theSchoolAsksOutsideTheSummerHolidaysAndOnlyWithHealthyAnimals() {
        props.getFormulas().getSchoolVisit().setProbabilityPerMonth(1.0);
        school.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime())); // July: holidays
        assertThat(cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.SCHOOL_VISIT))).isEmpty();

        sg.setCalPeriod(8);
        stable(50);
        school.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.SCHOOL_VISIT))).isEmpty();

        stable(80);
        school.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        ServiceCase sc = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.SCHOOL_VISIT)).getFirst();
        assertThat(sc.getCharacter().getRole()).isEqualTo(CharacterRole.SCHOOL);
        assertThat(sc.getDeadlineGameTime() - sg.getCurrentGameTime()).isEqualTo(GameTime.days(5));

        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(150);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("GUEST_INCOME");
        assertThat(sc.getCharacter().getTrustScore()).isEqualTo(2);
        assertThat(publicActions.findBySavegameOrderByGameTimeAsc(sg)).anyMatch(e -> e.getType() == PublicActionType.SCHOOL_VISIT
                && e.getDelta() == 2);
        assertThat(jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "SCHOOL_VISIT_THANKS")).hasSize(1);
    }
}
