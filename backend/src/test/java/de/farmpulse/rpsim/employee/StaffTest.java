package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.tax.TaxService;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-P1 / R3-P2: the office clerk with more effect and the apprentice. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class StaffTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired OfficeClerkService clerks;
    @Autowired ApprenticeService apprentices;
    @Autowired SatisfactionService satisfaction;
    @Autowired WorkforceService workforce;
    @Autowired TaxService tax;
    @Autowired ContractActions actions;
    @Autowired ServiceCaseRepository cases;
    @Autowired ContractRepository contracts;
    @Autowired ForwardContractRepository forwards;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.snapshot(sg, 100_000);
    }

    private Employee employee(JobRole role, int skill, String name) {
        Character c = fx.character(sg, CharacterRole.EMPLOYEE, CharacterCategory.EMPLOYEE, name);
        return hiring.createEmployee(sg, c, role, skill, 2300);
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<String> reminders() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals("OFFICE_CLERK_REMINDER"))
                .map(j -> json.readTree(j.getFactsJson()).get("subject").asString()).toList();
    }

    private ServiceCase taxBill(long deadline, long amount) {
        ServiceCase b = new ServiceCase();
        b.setSavegame(sg);
        b.setKind(CaseKind.TAX_BILL);
        b.setStatus(CaseStatus.AWAITING_PLAYER);
        b.setCharacter(fx.character(sg, CharacterRole.TAX_OFFICE, CharacterCategory.MANDATORY, "Frau Kramer"));
        b.setReference(TaxService.PREPAYMENT);
        b.setTitle("Vorauszahlung Q1");
        b.setOfferAmount(amount);
        b.setCostAmount(0L);
        b.setGameTime(sg.getCurrentGameTime());
        b.setDeadlineGameTime(deadline);
        b.setCreatedAt(Instant.now());
        return cases.save(b);
    }

    private void advisor() {
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.TAX_ADVISOR);
        c.setStatus(ContractStatus.ACTIVE);
        c.setMonthlyAmount(200);
        c.setCreatedAt(Instant.now());
        contracts.save(c);
    }

    private void day() {
        clerks.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        tax.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    // ------------------------------------------------------------------------------------------ R3-P1

    @Test
    void theClerkLowersAuditsAndWithAnAdvisorTheSmallerFactorCounts() {
        RpsimProperties.OfficeClerk cfg = props.getFormulas().getOfficeClerk();
        assertThat(OfficeClerkService.auditFactor(100, cfg)).isEqualTo(0.5);
        assertThat(OfficeClerkService.auditFactor(40, cfg)).isCloseTo(0.8, within(1e-9));
        assertThat(OfficeClerkService.auditFactor(150, cfg)).isEqualTo(0.5);
        assertThat(tax.auditFactor(sg)).isEqualTo(1.0); // nobody
        Employee weak = employee(JobRole.OFFICE_CLERK, 40, "Uta Schreiber");
        Employee strong = employee(JobRole.OFFICE_CLERK, 90, "Rita Ordner");
        assertThat(clerks.bestClerk(sg)).contains(strong);
        double effective = satisfaction.needs(strong).effectiveSkill();
        assertThat(tax.auditFactor(sg)).isCloseTo(1 - 0.5 * effective / 100, within(1e-9));
        assertThat(clerks.auditFactor(sg)).isLessThan(OfficeClerkService.auditFactor(40, cfg));
        advisor(); // factor 0.5 is smaller than the clerk's
        assertThat(tax.auditFactor(sg)).isEqualTo(0.5);
        assertThat(weak.getId()).isNotNull();
    }

    @Test
    void theClerkRemindsOnceBeforeEveryKindOfDeadline() {
        Employee clerk = employee(JobRole.OFFICE_CLERK, 70, "Rita Ordner");
        long now = sg.getCurrentGameTime();
        taxBill(now + 2 * DAY, 4000);
        taxBill(now + 5 * DAY, 1000); // too early
        ServiceCase inspection = new ServiceCase();
        inspection.setSavegame(sg);
        inspection.setKind(CaseKind.AUTHORITY_INSPECTION);
        inspection.setStatus(CaseStatus.IN_PROGRESS);
        inspection.setTitle("CULTIVATION_DUTY");
        inspection.setGameTime(now);
        inspection.setDeadlineGameTime(now + DAY);
        inspection.setCreatedAt(Instant.now());
        cases.save(inspection);
        ForwardContract fc = new ForwardContract();
        fc.setSavegame(sg);
        fc.setFillType("WHEAT");
        fc.setSellPoint("MillNorth");
        fc.setQuantity(10_000);
        fc.setFixedPrice(220);
        fc.setBasePrice(230);
        fc.setLeadMonths(1);
        fc.setDeliveryStartGameTime(now);
        fc.setDeadlineGameTime(now + 3 * DAY);
        fc.setStatus(ForwardContract.OPEN);
        fc.setCreatedGameTime(now);
        forwards.save(fc);
        Contract lease = new Contract();
        lease.setSavegame(sg);
        lease.setKind(ContractKind.LEASE);
        lease.setStatus(ContractStatus.ACTIVE);
        lease.setFarmlandId(13);
        lease.setMonthlyAmount(300);
        lease.setEndsAtGameTime(now + 2 * DAY);
        lease.setCreatedAt(Instant.now());
        contracts.save(lease);

        clerks.onDay(new GameDayPassedEvent(sg.getId(), 10, now));
        assertThat(reminders()).containsExactlyInAnyOrder("TAX_BILL", "INSPECTION", "FORWARD_CONTRACT", "LEASE_END");
        clerks.onDay(new GameDayPassedEvent(sg.getId(), 10, now)); // once per deadline
        assertThat(reminders()).hasSize(4);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getFirst().getCharacter()).isEqualTo(clerk.getCharacter());
        sg.setCurrentGameTime(now + 2 * DAY + 1);
        clerks.onDay(new GameDayPassedEvent(sg.getId(), 12, sg.getCurrentGameTime()));
        assertThat(reminders()).hasSize(5).last().isEqualTo("TAX_BILL"); // the second bill, now 3 days ahead
    }

    @Test
    void withATaxAdvisorTheClerkLeavesTheBillReminderToHim() {
        employee(JobRole.OFFICE_CLERK, 70, "Rita Ordner");
        advisor();
        taxBill(sg.getCurrentGameTime() + 2 * DAY, 4000);
        clerks.onDay(new GameDayPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        assertThat(reminders()).isEmpty();
    }

    @Test
    void theClerkPaysABillOnTheDeadlineDayUnlessSheIsOverloaded() {
        Employee clerk = employee(JobRole.OFFICE_CLERK, 70, "Rita Ordner");
        long now = sg.getCurrentGameTime();
        ServiceCase early = taxBill(now + 2 * DAY, 4000);
        ServiceCase due = taxBill(now + DAY / 2, 4000);
        day();
        assertThat(early.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER); // not before the deadline day
        assertThat(due.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(due.getResolution()).isEqualTo("PAID");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anySatisfy(o -> {
            assertThat(o.getType()).isEqualTo(InstructionType.MONEY_TRANSACTION);
            assertThat(o.getPayloadJson()).contains("TAX_PAYMENT").contains("-4000");
        });
        assertThat(narrations()).contains("OFFICE_CLERK_PAID");

        // overloaded: the bill stays, after the deadline the late fee follows as before
        clerk.setWorkload(20);
        clerk.setNeedsUpdatedAtGameTime(sg.getCurrentGameTime());
        ServiceCase late = taxBill(now + DAY / 2, 4000);
        day();
        assertThat(late.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        sg.setCurrentGameTime(now + DAY);
        tax.onDay(new GameDayPassedEvent(sg.getId(), 11, sg.getCurrentGameTime()));
        assertThat(late.getCostAmount()).isPositive();
    }

    @Test
    void theClerkDoesNotPayWithoutMoney() {
        employee(JobRole.OFFICE_CLERK, 70, "Rita Ordner");
        ServiceCase due = taxBill(sg.getCurrentGameTime() + DAY / 2, 150_000);
        day();
        assertThat(due.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(narrations()).doesNotContain("OFFICE_CLERK_PAID");
    }

    // ------------------------------------------------------------------------------------------ R3-P2

    @Test
    void apprenticeApplicantsAreCheapAndLimitedToTwo() {
        JobPosting p = hiring.createPosting(sg, JobRole.APPRENTICE);
        sg.setCurrentGameTime(11 * DAY + GameTime.hours(18)); // the applications arrive the next game day
        List<JobApplication> apps = hiring.applications(sg, p.getId());
        assertThat(apps).isNotEmpty().allSatisfy(a -> {
            assertThat(a.getSkill()).isBetween(10, 30);
            assertThat(a.getExpectedSalary()).isEqualTo(900);
            assertThat(a.getTraining()).isNull();
        });
        Employee a = hiring.hire(sg, p.getId(), apps.getFirst().getId());
        assertThat(a.getJobRole()).isEqualTo(JobRole.APPRENTICE);
        assertThat(a.getStatus()).isEqualTo(EmployeeStatus.PENDING_START);
        // starts on day 12 (next month) + 2 FS25 years of 1-day months
        assertThat(a.getApprenticeshipEndsAtGameTime()).isEqualTo(36 * DAY);
        employee(JobRole.APPRENTICE, 20, "Tim Lehrling"); // with the one starting next month: two
        assertThatThrownBy(() -> hiring.createPosting(sg, JobRole.APPRENTICE)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("2 Azubis");
        // from his first working day on the apprentice is a driver without trainings in the roster
        sg.setCurrentGameTime(a.getStartsAtGameTime());
        hiring.startDue(sg);
        Map<String, Object> entry = workforce.roster(sg).stream().filter(m -> m.get("employeeId").equals(a.getId()))
                .findFirst().orElseThrow();
        assertThat(entry.get("role")).isEqualTo("APPRENTICE");
        assertThat((List<?>) entry.get("trainings")).isEmpty();
    }

    @Test
    void theApprenticeLearnsEveryMonthUpToTheCap() {
        Employee a = employee(JobRole.APPRENTICE, 66, "Tim Lehrling");
        apprentices.onMonth(new GameMonthPassedEvent(sg.getId(), 11, 11 * DAY));
        assertThat(a.getSkill()).isEqualTo(68);
        apprentices.onMonth(new GameMonthPassedEvent(sg.getId(), 12, 12 * DAY));
        apprentices.onMonth(new GameMonthPassedEvent(sg.getId(), 13, 13 * DAY));
        assertThat(a.getSkill()).isEqualTo(70);
    }

    private ServiceCase takeoverRequest(Employee a) {
        sg.setCurrentGameTime(a.getApprenticeshipEndsAtGameTime() - DAY); // one month (= 1 day) before the end
        apprentices.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.APPRENTICE_TAKEOVER)).getFirst();
    }

    @Test
    void anApprenticeAsksToBeTakenOverAndBecomesAMachineOperator() {
        Employee a = employee(JobRole.APPRENTICE, 50, "Tim Lehrling");
        sg.setCurrentGameTime(a.getApprenticeshipEndsAtGameTime() - 3 * DAY);
        apprentices.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.APPRENTICE_TAKEOVER))).isEmpty();
        ServiceCase sc = takeoverRequest(a);
        assertThat(sc.getOfferAmount()).isEqualTo(apprentices.operatorSalary(50)).isEqualTo(2400);
        assertThat(sc.getDeadlineGameTime()).isEqualTo(a.getApprenticeshipEndsAtGameTime());
        assertThat(narrations()).contains("APPRENTICE_TAKEOVER_REQUEST");
        actions.acceptCase(sg, sc.getId());
        assertThat(a.getJobRole()).isEqualTo(JobRole.MACHINE_OPERATOR);
        assertThat(a.getMonthlySalary()).isEqualTo(2400);
        assertThat(a.getApprenticeshipEndsAtGameTime()).isNull();
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + 2 * DAY);
        apprentices.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(a.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void aCounterOfferFromNinetyPercentIsAcceptedBelowHeLeavesAtTheEnd() {
        Employee good = employee(JobRole.APPRENTICE, 50, "Tim Lehrling");
        ServiceCase sc = takeoverRequest(good);
        actions.counterCase(sg, sc.getId(), 2160); // 90 % of 2,400
        assertThat(good.getJobRole()).isEqualTo(JobRole.MACHINE_OPERATOR);
        assertThat(good.getMonthlySalary()).isEqualTo(2160);

        Employee cheap = employee(JobRole.APPRENTICE, 50, "Lea Lehrling");
        ServiceCase low = takeoverRequest(cheap);
        actions.counterCase(sg, low.getId(), 2150);
        assertThat(low.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(low.getResolution()).isEqualTo("COUNTER_REJECTED");
        assertThat(cheap.getStatus()).isEqualTo(EmployeeStatus.ACTIVE); // finishes the training
        assertThat(narrations()).contains("APPRENTICE_LEAVES");
        sg.setCurrentGameTime(cheap.getApprenticeshipEndsAtGameTime());
        apprentices.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(cheap.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
    }

    @Test
    void withoutAnAnswerTheApprenticeLeavesAtTheEnd() {
        Employee a = employee(JobRole.APPRENTICE, 40, "Tim Lehrling");
        ServiceCase sc = takeoverRequest(a);
        sg.setCurrentGameTime(a.getApprenticeshipEndsAtGameTime());
        apprentices.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(a.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThatThrownBy(() -> actions.acceptCase(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);
    }
}
