package de.farmpulse.rpsim.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.DirectPaymentApplication;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.field.FieldDocs;
import de.farmpulse.rpsim.field.FieldService;
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

/** Roadmap V3.1 R31-B1: area payment application (owner decisions 2026-10-05). One game day per FS25 period. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DirectPaymentTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired DirectPaymentService payments;
    @Autowired FieldService fields;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    long t0;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t0 = sg.getCurrentGameTime(); // start of March (FS25 period 1)
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(t0);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
        props.getFormulas().getDirectPayment().setCheckProbability(0.0);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setDirectPayment(new RpsimProperties.DirectPayment());
    }

    private void at(long time) {
        sg.setCurrentGameTime(time);
    }

    private void facts(int year, String... docs) {
        long t = sg.getCurrentGameTime();
        var s = fx.snapshot(sg, t, 100_000, FieldDocs.doc(sg.getBridgeSavegameId(), t, year, FieldDocs.ALL_RULES, null, docs));
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private static String crop(int farmland, String crop) {
        return FieldDocs.field(farmland, crop, 5, false, false, 0, 0, 1, 1);
    }

    private void month() {
        payments.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void day() {
        payments.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<OutboxInstruction> money() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .toList();
    }

    private DirectPaymentApplication opened() {
        facts(1, crop(12, "WHEAT"), crop(13, "BARLEY"));
        month();
        return payments.list(sg).getFirst();
    }

    @Test
    void theFormOpensInMarchWithTheDeadlineAtTheEndOfMay() {
        DirectPaymentApplication a = opened();
        assertThat(a.getStatus()).isEqualTo(DirectPaymentApplication.OPEN);
        assertThat(a.getCropYear()).isEqualTo(1);
        assertThat(a.getDeadlineGameTime()).isEqualTo(t0 + 3 * DAY); // end of period 3 = start of period 4
        assertThat(payments.lateLimit(a)).isEqualTo(t0 + 28 * DAY);
        assertThat(narrations()).contains("DIRECT_PAYMENT_OPEN");
        assertThat(payments.form(sg)).extracting(DirectPaymentService.FormField::suggestedCrop)
                .containsExactly("WHEAT", "BARLEY");
        assertThat(payments.crops(sg)).contains("WHEAT", "GRASS", "BRACHE");

        month(); // once per year
        assertThat(payments.list(sg)).hasSize(1);
        sg.setCalPeriod(2); // not the opening month
        facts(2, crop(12, "WHEAT"));
        month();
        assertThat(payments.list(sg)).hasSize(1);
    }

    @Test
    void aLateApplicationIsCutPerDayAndPaidInDecember() {
        DirectPaymentApplication a = opened();
        assertThatThrownBy(() -> payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(99, "WHEAT"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("gehört nicht zum Hof");
        assertThatThrownBy(() -> payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(12, "BANANA"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Unbekannte Kultur");

        at(t0 + 3 * DAY + DAY / 2); // half a day after the deadline: one started day late
        payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(12, "WHEAT"),
                new DirectPaymentService.Declared(13, "BARLEY")));
        assertThat(a.getStatus()).isEqualTo(DirectPaymentApplication.SUBMITTED);
        assertThat(a.getLateDays()).isEqualTo(1);

        at(t0 + 9 * DAY); // December (period 10)
        month();
        assertThat(a.getStatus()).isEqualTo(DirectPaymentApplication.PAID);
        assertThat(a.getPremium()).isEqualTo(2250); // 9 ha x 250 €
        assertThat(a.getLateCut()).isEqualTo(23); // 1 % (22.5, rounded)
        var p = json.readTree(money().getFirst().getPayloadJson());
        assertThat(p.path("amount").asLong()).isEqualTo(2227);
        assertThat(p.path("reason").asString()).isEqualTo("DIRECT_PAYMENT");
        assertThat(narrations()).contains("DIRECT_PAYMENT_PAID");
    }

    @Test
    void withoutApplicationWithinTheGraceDaysThereIsNoPremium() {
        DirectPaymentApplication a = opened();
        at(t0 + 28 * DAY + DAY / 2);
        day();
        assertThat(a.getStatus()).isEqualTo(DirectPaymentApplication.LAPSED);
        assertThat(narrations()).contains("DIRECT_PAYMENT_LAPSED");
        assertThatThrownBy(() -> payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(12, "WHEAT"))))
                .isInstanceOf(BusinessRuleException.class);
        at(t0 + 9 * DAY + 30 * DAY);
        month();
        assertThat(money()).isEmpty();
    }

    @Test
    void theOnSiteCheckCutsDeviatingCropsAndRepeatedRotation() {
        props.getFormulas().getDirectPayment().setCheckProbability(1.0);
        facts(0, crop(12, "WHEAT"), crop(13, "BARLEY"), crop(14, "BARLEY"));
        at(t0 + DAY / 4);
        facts(1, crop(12, "WHEAT"), crop(13, "MAIZE"), crop(14, "OAT")); // year 0 closed
        month();
        DirectPaymentApplication a = payments.list(sg).getFirst();
        payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(12, "WHEAT"),
                new DirectPaymentService.Declared(13, "SOYBEAN"), new DirectPaymentService.Declared(14, "OAT")));

        at(t0 + 3 * DAY); // June (period 4): rolled and announced
        month();
        assertThat(a.getCheckStatus()).isEqualTo(DirectPaymentApplication.CHECK_ANNOUNCED);
        ServiceCase sc = cases.findById(a.getCheckCaseId()).orElseThrow();
        assertThat(sc.getKind()).isEqualTo(CaseKind.AUTHORITY_INSPECTION);
        assertThat(sc.getTitle()).isEqualTo(DirectPaymentService.RULE);

        at(sc.getDeadlineGameTime());
        day();
        assertThat(a.getCheckStatus()).isEqualTo(DirectPaymentApplication.CHECK_DONE);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(a.getDeviatingHectares()).isEqualTo(4.5); // field 13: MAIZE, declared SOYBEAN
        assertThat(a.getDeviationCut()).isEqualTo(1688); // 4.5 ha x 250 € x 1.5
        assertThat(a.getRotationCut()).isEqualTo(563); // field 12 WHEAT again: 4.5 ha x 250 € x 0.5
        assertThat(narrations()).contains("AUTHORITY_INSPECTION_NOTICE", "DIRECT_PAYMENT_CHECK_RESULT");

        at(t0 + 9 * DAY);
        month();
        assertThat(a.getPaidAmount()).isEqualTo(3375 - 1688 - 563);
    }

    @Test
    void noCheckWhenSwitchedOff() {
        props.getFormulas().getDirectPayment().setCheckProbability(1.0);
        sg.setBurdenAreaCheck(false);
        DirectPaymentApplication a = opened();
        payments.submit(sg, a.getId(), List.of(new DirectPaymentService.Declared(12, "WHEAT")));
        at(t0 + 3 * DAY);
        month();
        assertThat(a.getCheckStatus()).isEqualTo(DirectPaymentApplication.CHECK_NOT_SELECTED);
    }
}
