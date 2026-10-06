package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.authority.SocialInsuranceService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-B5: social insurance fee, sickness and work accidents (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class SickLeaveTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired SickLeaveService sickLeave;
    @Autowired SocialInsuranceService socialInsurance;
    @Autowired SatisfactionService satisfaction;
    @Autowired ContractActions actions;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(2); // April
        fx.snapshot(sg, 100_000);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setSickLeave(new RpsimProperties.SickLeave());
    }

    private Employee employee(JobRole role, String name) {
        Character c = fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, name);
        return hiring.createEmployee(sg, c, role, 60, 2300);
    }

    private void day() {
        sickLeave.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    @Test
    void theAnnualFeeIsBilledInAprilAndPaidByButton() {
        employee(JobRole.MACHINE_OPERATOR, "Jonas Meyer");
        employee(JobRole.SEASONAL_WORKER, "Ida Kuhn");
        socialInsurance.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        socialInsurance.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime())); // once per year
        List<ServiceCase> bills = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.SOCIAL_INSURANCE_BILL));
        assertThat(bills).hasSize(1);
        ServiceCase b = bills.getFirst();
        // 300 + 4.5 ha (farmland of the assets) x 12 + 2 employees x 180
        assertThat(b.getOfferAmount()).isEqualTo(300 + 54 + 360);
        assertThat(b.getCharacter().getRole()).isEqualTo(CharacterRole.SOCIAL_INSURANCE);
        assertThat(narrations()).contains("SOCIAL_INSURANCE_BILL");

        actions.acceptCase(sg, b.getId());
        assertThat(b.getStatus()).isEqualTo(CaseStatus.SETTLED);
        var money = outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION).toList();
        var p = json.readTree(money.getLast().getPayloadJson());
        assertThat(p.path("amount").asLong()).isEqualTo(-714);
        assertThat(p.path("reason").asString()).isEqualTo("SOCIAL_INSURANCE");
    }

    @Test
    void aSickEmployeeIsOnLeaveForSomeDaysAndThanksForTheWishes() {
        Employee clerk = employee(JobRole.OFFICE_CLERK, "Petra Hansen");
        Employee e = employee(JobRole.MACHINE_OPERATOR, "Jonas Meyer");
        sickLeave.start(sg, e, SickLeaveService.SICKNESS, 3);
        assertThat(WorkforceService.rosterStatus(e, sg.getCurrentGameTime())).isEqualTo(WorkforceService.STATUS_ON_LEAVE);
        assertThat(narrations()).contains("EMPLOYEE_SICK");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals("EMPLOYEE_SICK"))
                .findFirst().orElseThrow().getCharacter().getId()).isEqualTo(clerk.getCharacter().getId()); // the clerk reports

        double before = satisfaction.needs(e).appreciation();
        sickLeave.getWell(sg, e);
        assertThat(satisfaction.needs(e).appreciation()).isGreaterThan(before);
        assertThat(narrations()).contains("EMPLOYEE_GET_WELL_THANKS");
        assertThatThrownBy(() -> sickLeave.getWell(sg, e)).isInstanceOf(BusinessRuleException.class);

        sg.setCurrentGameTime(sg.getCurrentGameTime() + 3 * DAY);
        day();
        assertThat(e.getAbsenceKind()).isNull();
        assertThat(WorkforceService.rosterStatus(e, sg.getCurrentGameTime())).isEqualTo(WorkforceService.STATUS_ACTIVE);
        assertThatThrownBy(() -> sickLeave.getWell(sg, e)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void accidentsHitDrivingRolesAndAreReportedToTheSocialInsurance() {
        props.getFormulas().getSickLeave().setAccidentProbabilityPerDay(2.0); // certain even x 0.5 under good conditions
        Employee keeper = employee(JobRole.ANIMAL_KEEPER, "Greta Lüders");
        Employee driver = employee(JobRole.MACHINE_OPERATOR, "Jonas Meyer");
        day();
        assertThat(keeper.getAbsenceKind()).isNull(); // not an accident role
        assertThat(driver.getAbsenceKind()).isEqualTo(SickLeaveService.ACCIDENT);
        assertThat(driver.getAbsenceUntilGameTime() - sg.getCurrentGameTime()).isBetween(3 * DAY, 10 * DAY);
        assertThat(narrations()).contains("EMPLOYEE_SICK", "WORK_ACCIDENT_REPORT");
    }

    @Test
    void riskFactorAndSwitches() {
        RpsimProperties.SickLeave cfg = props.getFormulas().getSickLeave();
        assertThat(SickLeaveService.riskFactor(30, 30, cfg)).isEqualTo(2.25);
        assertThat(SickLeaveService.riskFactor(30, 60, cfg)).isEqualTo(1.5);
        assertThat(SickLeaveService.riskFactor(60, 60, cfg)).isEqualTo(1.0);
        assertThat(SickLeaveService.riskFactor(80, 75, cfg)).isEqualTo(0.5);

        cfg.setSicknessProbabilityPerDay(1.0);
        Employee e = employee(JobRole.MACHINE_OPERATOR, "Jonas Meyer");
        sg.setBurdenSickLeave(false);
        day();
        assertThat(e.getAbsenceKind()).isNull();
        sg.setBurdenSickLeave(true);
        sg.setTonePreset(TonePreset.IDYLLIC); // chance x 0.5: 2.0 x 0.5 stays certain
        cfg.setSicknessProbabilityPerDay(2.0);
        day();
        assertThat(e.getAbsenceKind()).isEqualTo(SickLeaveService.SICKNESS);
    }
}
