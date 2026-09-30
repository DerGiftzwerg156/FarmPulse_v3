package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Objects;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobApplication;
import de.farmpulse.rpsim.domain.JobPosting;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owner decision "Schulungen": price, one game day away, qualification afterwards, list for the mod, applicants. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class TrainingServiceTest {

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired TrainingService training;
    @Autowired WorkforceService workforce;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000);
    }

    private Employee employee(JobRole role) {
        var c = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Klaus Berger");
        return hiring.createEmployee(sg, c, role, 70, 2400);
    }

    private List<OutboxInstruction> trainingBookings() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION && o.getPayloadJson().contains("\"TRAINING\""))
                .toList();
    }

    private JsonNode lastRoster() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.EMPLOYEE_ROSTER)
                .map(OutboxInstruction::getPayloadJson).map(json::readTree).toList().getLast();
    }

    @Test
    void newEmployeesHaveNoTrainingAndTheListCarriesTheCategories() {
        employee(JobRole.MACHINE_OPERATOR);
        JsonNode roster = lastRoster();
        assertThat(roster.path("employees").get(0).path("trainings").isArray()).isTrue();
        assertThat(roster.path("employees").get(0).path("trainings")).isEmpty();
        assertThat(roster.path("trainingCategories").path("COMBINE").get(0).asString()).isEqualTo("HARVESTERS");
        assertThat(roster.path("trainingCategories").path("LARGE_TRACTOR").get(0).asString()).isEqualTo("TRACTORSL");
        assertThat(roster.path("trainingCategories").has("TRACTORSM")).isFalse();
    }

    @Test
    void aTrainingCostsMoneyTakesOneDayAndQualifiesAfterwards() {
        Employee e = employee(JobRole.MACHINE_OPERATOR);
        double appreciation = e.getAppreciation();
        long start = sg.getCurrentGameTime();

        training.book(e, Training.COMBINE);

        assertThat(trainingBookings()).hasSize(1);
        JsonNode payload = json.readTree(trainingBookings().getFirst().getPayloadJson());
        assertThat(payload.path("amount").asLong()).isEqualTo(-9000);
        assertThat(e.getTrainingInProgress()).isEqualTo(Training.COMBINE);
        assertThat(e.getTrainingUntilGameTime()).isEqualTo(start + GameTime.days(1));
        assertThat(e.hasTraining(Training.COMBINE)).as("only after the training").isFalse();
        assertThat(e.getAppreciation()).isCloseTo(appreciation + 8, within(1e-6));
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType).contains("EMPLOYEE_THANKS");
        assertThat(lastRoster().path("employees").get(0).path("status").asString()).as("away: no helper")
                .isEqualTo("ON_LEAVE");
        assertThatThrownBy(() -> training.book(e, Training.TRUCK)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("gerade schon");

        sg.setCurrentGameTime(start + GameTime.days(0.9));
        assertThat(workforce.sync(sg)).isFalse();
        assertThat(e.hasTraining(Training.COMBINE)).isFalse();

        sg.setCurrentGameTime(start + GameTime.days(1));
        assertThat(workforce.sync(sg)).isTrue();
        assertThat(e.hasTraining(Training.COMBINE)).isTrue();
        assertThat(e.getTrainingInProgress()).isNull();
        JsonNode emp = lastRoster().path("employees").get(0);
        assertThat(emp.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(emp.path("trainings").get(0).asString()).isEqualTo("COMBINE");
        assertThatThrownBy(() -> training.book(e, Training.COMBINE)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    void onlyAvailableMachineOperatorsCanBeTrained() {
        Employee mechanic = employee(JobRole.MECHANIC);
        assertThatThrownBy(() -> training.book(mechanic, Training.TRUCK)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Maschinenführer");
        Employee striking = employee(JobRole.MACHINE_OPERATOR);
        striking.setStrikeSinceGameTime(sg.getCurrentGameTime());
        assertThatThrownBy(() -> training.book(striking, Training.TRUCK)).isInstanceOf(BusinessRuleException.class);
        Employee onLeave = employee(JobRole.MACHINE_OPERATOR);
        onLeave.setTimeOffUntilGameTime(sg.getCurrentGameTime() + GameTime.days(2));
        assertThatThrownBy(() -> training.book(onLeave, Training.TRUCK)).isInstanceOf(BusinessRuleException.class);
        assertThat(trainingBookings()).isEmpty();
    }

    @Test
    void aRefusedBookingCancelsTheTraining() {
        Employee e = employee(JobRole.MACHINE_OPERATOR);
        training.book(e, Training.TRUCK);
        OutboxInstruction ins = trainingBookings().getFirst();
        ins.setStatus(InstructionStatus.REJECTED);
        ins.setAckedAtGameTime(sg.getCurrentGameTime());
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), "REJECTED",
                ins.getRelatedEntityType(), ins.getRelatedEntityId()));
        assertThat(e.getTrainingInProgress()).isNull();
        assertThat(e.hasTraining(Training.TRUCK)).isFalse();
        assertThat(lastRoster().path("employees").get(0).path("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void operatorApplicantsSometimesBringOneTrainingAndWantMoreSalary() {
        int trained = 0;
        int total = 0;
        for (int i = 0; i < 200; i++) {
            long seed = 1000L + i;
            var plain = hiring.rollOffer(JobRole.MACHINE_OPERATOR, RandomSource.seeded(seed));
            var offer = hiring.rollApplicant(JobRole.MACHINE_OPERATOR, RandomSource.seeded(seed));
            assertThat(offer.skill()).isEqualTo(plain.skill());
            total++;
            if (offer.training() != null) {
                trained++;
                assertThat(offer.salary()).isEqualTo(Math.round(plain.salary() * 1.08 / 10.0) * 10);
            } else {
                assertThat(offer.salary()).isEqualTo(plain.salary());
            }
            assertThat(hiring.rollApplicant(JobRole.MECHANIC, RandomSource.seeded(seed)).training()).isNull();
        }
        assertThat(trained / (double) total).isBetween(0.15, 0.45);
    }

    @Test
    void aHiredApplicantKeepsHisTraining() {
        JobApplication withTraining = null;
        for (int i = 0; i < 20 && withTraining == null; i++) {
            JobPosting p = hiring.createPosting(sg, JobRole.MACHINE_OPERATOR);
            withTraining = hiring.applications(sg, p.getId()).stream().filter(a -> a.getTraining() != null)
                    .findFirst().orElse(null);
        }
        assertThat(withTraining).as("an applicant with a training within 20 postings").isNotNull();
        Training t = Objects.requireNonNull(withTraining).getTraining();
        Employee e = hiring.hire(sg, withTraining.getPosting().getId(), withTraining.getId());
        assertThat(e.trainingSet()).containsExactly(t);
        JsonNode hired = null;
        for (JsonNode n : lastRoster().path("employees")) {
            if (n.path("employeeId").asLong() == e.getId()) {
                hired = n;
            }
        }
        assertThat(hired).isNotNull();
        assertThat(Objects.requireNonNull(hired).path("trainings").get(0).asString()).isEqualTo(t.name());
    }
}
