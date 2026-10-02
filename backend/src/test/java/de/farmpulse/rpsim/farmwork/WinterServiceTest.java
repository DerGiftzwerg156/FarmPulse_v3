package de.farmpulse.rpsim.farmwork;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.MachineLoanRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
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
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-A4: winter service for the municipality (owner decisions 2026-10-02). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class WinterServiceTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired WinterServiceService winter;
    @Autowired ContractActions actions;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        // one game day per month; the current game time is the start of October (FS25 period 8)
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(8);
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Gemeinde Erlengrund");
    }

    @AfterEach
    void restore() {
        props.getFormulas().setWinterService(new RpsimProperties.WinterService());
    }

    /** Facts at the current game time with a vehicle of {@code category} and the snow height (null = not reported). */
    private FactsSnapshot snapshot(String category, Double snowHeight) {
        String doc = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000);
        if (category != null) {
            doc = doc.replace("\"condition\": 82 }", "\"condition\": 82, \"category\": \"" + category + "\" }");
        }
        doc = TestData.withFields(doc, "\"weather\": { \"raining\": false, \"rainFallScale\": 0, \"groundWetness\": 0"
                + (snowHeight == null ? "" : ", \"snowHeight\": " + snowHeight) + " }");
        return fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, doc);
    }

    private void month() {
        winter.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void advance(long ms) {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + ms);
    }

    private void facts(Double snowHeight) {
        FactsSnapshot s = snapshot("TRACTORSL", snowHeight);
        winter.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), sg.getCurrentGameTime(), false));
    }

    private List<OutboxInstruction> of(InstructionType type) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == type).toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private Contract offered() {
        snapshot("TRACTORSL", 0.0);
        month();
        return winter.current(sg).orElseThrow();
    }

    @Test
    void offeredInOctoberOnlyWithQualifyingVehicleAndSnowHeight() {
        snapshot("TRACTORSL", null); // older mod or snow switched off: no snow height
        month();
        assertThat(winter.current(sg)).isEmpty();

        snapshot("HARVESTERS", 0.0); // no vehicle of TRACTORSM / TRACTORSL
        month();
        assertThat(winter.current(sg)).isEmpty();

        sg.setCalPeriod(7); // September: not the offer month
        snapshot("TRACTORSM", 0.0);
        month();
        assertThat(winter.current(sg)).isEmpty();

        sg.setCalPeriod(8);
        month();
        Contract c = winter.current(sg).orElseThrow();
        assertThat(c.getKind()).isEqualTo(ContractKind.WINTER_SERVICE);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.OFFERED);
        assertThat(c.getMonthlyAmount()).isEqualTo(400);
        assertThat(c.getTermMonths()).isEqualTo(4);
        assertThat(c.getCharacter().getRole()).isEqualTo(CharacterRole.AUTHORITY);
        assertThat(c.getOfferExpiresAtGameTime()).isEqualTo(sg.getCurrentGameTime() + DAY);
        assertThat(narrations()).containsExactly("WINTER_SERVICE_OFFER");

        month(); // a second October run does not offer twice
        assertThat(winter.contracts(sg)).hasSize(1);
    }

    @Test
    void offerExpiresAtWinterStart() {
        Contract c = offered();
        advance(DAY); // November
        month();
        assertThat(c.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(c.getEndReason()).isEqualTo("EXPIRED");
    }

    @Test
    void declineViaContractActions() {
        Contract c = offered();
        actions.decline(sg, c.getId());
        assertThat(c.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(c.getEndReason()).isEqualTo("PLAYER");
    }

    @Test
    void snowDaysCountOncePerDayAndArePaidAtMonthStartUntilTheWinterEnds() {
        Contract c = offered();
        actions.accept(sg, c.getId());
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(c.getNextDueGameTime()).isNull(); // the generic billing does not touch it

        facts(0.3); // October is no winter month
        assertThat(c.getSnowDays()).isZero();

        advance(DAY); // November (period 9)
        month();
        assertThat(of(InstructionType.MONEY_TRANSACTION)).isEmpty(); // October was no winter month
        facts(0.02); // below the 0.05 m threshold
        assertThat(c.getSnowDays()).isZero();
        facts(0.05);
        advance(DAY / 4);
        facts(0.4); // same day: counted once
        assertThat(c.getSnowDays()).isEqualTo(1);
        List<OutboxInstruction> hints = of(InstructionType.NOTIFICATION);
        assertThat(hints).hasSize(1);
        assertThat(json.readTree(hints.get(0).getPayloadJson()).path("text").asString())
                .isEqualTo("Schnee! Winterdienst ab 5 Uhr");

        sg.setCurrentGameTime(sg.getCalMonthStartGameTime() + 2 * DAY); // December: pays November
        month();
        List<OutboxInstruction> money = of(InstructionType.MONEY_TRANSACTION);
        assertThat(money).hasSize(1);
        var p = json.readTree(money.get(0).getPayloadJson());
        assertThat(p.path("amount").asLong()).isEqualTo(400 + 150);
        assertThat(p.path("reason").asString()).isEqualTo("WINTER_SERVICE");
        assertThat(c.getSnowDays()).isZero();
        assertThat(c.getSnowDaysTotal()).isEqualTo(1);

        for (int m = 3; m <= 5; m++) { // January, February, March: pay December, January, February (no snow)
            sg.setCurrentGameTime(sg.getCalMonthStartGameTime() + m * DAY);
            month();
        }
        money = of(InstructionType.MONEY_TRANSACTION);
        assertThat(money).hasSize(4);
        assertThat(json.readTree(money.get(3).getPayloadJson()).path("amount").asLong()).isEqualTo(400);
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("WINTER_OVER");
        assertThat(narrations()).containsExactly("WINTER_SERVICE_OFFER", "WINTER_SERVICE_ENDED");

        advance(DAY); // April: an ended contract pays nothing more
        month();
        assertThat(of(InstructionType.MONEY_TRANSACTION)).hasSize(4);
    }

    @Test
    void renewalDependsOnSnowInTheLastWinter() {
        Contract c = offered();
        actions.accept(sg, c.getId());
        c.setStatus(ContractStatus.ENDED); // a winter without snow
        c.setSnowDaysTotal(0);
        props.getFormulas().getWinterService().setRenewalProbabilityWithoutSnow(0.0);
        props.getFormulas().getWinterService().setRenewalProbability(1.0);
        month();
        assertThat(winter.current(sg)).isEmpty();

        c.setSnowDaysTotal(3); // a winter with snow
        month();
        assertThat(winter.current(sg)).isPresent();
    }

    @Test
    void borrowedMachinesDoNotQualify(@Autowired MachineLoanRepository loans) {
        MachineLoan l = new MachineLoan(); // the only TRACTORSL is a borrowed machine (R31-A2)
        l.setSavegame(sg);
        l.setKind(MachineLoan.LOAN);
        l.setStatus(MachineLoan.ACTIVE);
        l.setCharacter(fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen"));
        l.setStoreXmlFilename("data/vehicles/fendt/vario700.xml");
        l.setVehicleName("Fendt 700 Vario");
        l.setCategoryName("TRACTORSL");
        l.setListPrice(245_000);
        l.setDays(3);
        l.setVehicleId("veh_00042");
        loans.save(l);
        snapshot("TRACTORSL", 0.0);
        month();
        assertThat(winter.current(sg)).isEmpty();
    }
}
