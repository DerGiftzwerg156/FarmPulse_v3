package de.farmpulse.rpsim.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.field.FieldDocs;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V2 R2-E2: rotation, cultivation duty and animal welfare. A game month is one game day here. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class AuthorityServiceTest {

    @Autowired Fixtures fx;
    @Autowired AuthorityService authority;
    @Autowired FieldService fields;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired PublicActionEventRepository publicActions;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
        // the cooperative (year congratulation of C4) stays silent without character
    }

    private void fieldsAt(double days, int year, String... docs) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.days(days);
        sg.setCurrentGameTime(t);
        var s = fx.snapshot(sg, t, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), t, year, FieldDocs.ALL_RULES, null, docs));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private void day() {
        authority.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<String> messages() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private static String crop(int farmland, String crop) {
        return FieldDocs.field(farmland, crop, 8, false, false, 0, 0, 1, 1);
    }

    @Test
    void theSameCropTwiceBringsANoticeARepeatCutsThePremium() {
        fieldsAt(0, 1, crop(12, "WHEAT"), crop(13, "WHEAT"));
        fieldsAt(1, 2, crop(12, "WHEAT"), crop(13, "BARLEY")); // year 1 closed (no previous year yet)
        fieldsAt(2, 3, crop(12, "WHEAT"), crop(13, "WHEAT")); // year 2 closed: 12 same crop, 13 changed
        assertThat(messages()).contains("AUTHORITY_ROTATION_NOTICE", "AUTHORITY_SUBSIDY");
        // premium 4.5 ha x 40 € for field 13
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).filteredOn(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .singleElement().satisfies(o -> assertThat(o.getPayloadJson()).contains("\"amount\":180", "SUBSIDY"));
        fieldsAt(3, 4, crop(12, "BARLEY"), crop(13, "BARLEY")); // year 3 closed: field 12 wheat again (2nd), 13 changed
        AuthorityService.RotationResult r = authority.rotation(sg, 3);
        assertThat(r.cut()).isTrue();
        assertThat(r.premium()).as("halved").isEqualTo(90);
    }

    @Test
    void aNeglectedFieldIsAnnouncedAndFinedAfterTheDeadline() {
        String weedy = FieldDocs.field(12, null, 0, null, null, 6, 0, 1, 1);
        fieldsAt(0, 1, weedy);
        day();
        assertThat(messages()).isEmpty();
        fieldsAt(3, 1, weedy);
        day();
        assertThat(messages()).containsExactly("AUTHORITY_INSPECTION_NOTICE");
        fieldsAt(9, 1, weedy); // still neglected after 5 days
        day();
        assertThat(messages()).last().isEqualTo("AUTHORITY_INSPECTION_RESULT");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("\"amount\":-500")
                && o.getPayloadJson().contains("FINE"));
    }

    @Test
    void aFieldCleanedInTimeIsInOrder() {
        String weedy = FieldDocs.field(12, null, 0, null, null, 6, 0, 1, 1);
        fieldsAt(0, 1, weedy);
        fieldsAt(3, 1, weedy);
        day();
        fieldsAt(9, 1, FieldDocs.field(12, "WHEAT", 1, false, false, 0, 0, 1, 1));
        day();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("IN_ORDER");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }

    private void stable(double days, double health, double food) {
        long t = 10 * GameTime.MS_PER_DAY + GameTime.days(days);
        sg.setCurrentGameTime(t);
        fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": " + health + ", \"food\": " + food
                        + ", \"conditions\": [{ \"title\": \"Wasser\", \"ratio\": 0.8 }] }]"));
        day();
    }

    @Test
    void animalWelfareFirstBringsARequirementThenAFineAndLessReputation() {
        stable(0, 20, 0.5);
        stable(3, 20, 0.5);
        assertThat(messages()).containsExactly("AUTHORITY_INSPECTION_NOTICE");
        stable(9, 20, 0.5);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("REQUIREMENT");
        stable(15, 25, 0);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("FINED");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getPayloadJson().contains("\"amount\":-1000"));
        assertThat(publicActions.findAll()).anyMatch(a -> a.getType() == PublicActionType.AUTHORITY_FINE);
        assertThat(authority.inspections(sg)).allMatch(c -> c.getStatus() == CaseStatus.SETTLED);
    }

    @Test
    void atMostTwoInspectionsPerMonth() {
        fieldsAt(0, 1, FieldDocs.field(12, null, 0, null, null, 6, 0, 1, 1), FieldDocs.field(13, null, 0, null, null, 6, 0, 1, 1),
                FieldDocs.field(14, null, 0, null, null, 6, 0, 1, 1));
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(80));
        day();
        List<ServiceCase> open = authority.inspections(sg);
        assertThat(open).hasSize(2);
    }
}
