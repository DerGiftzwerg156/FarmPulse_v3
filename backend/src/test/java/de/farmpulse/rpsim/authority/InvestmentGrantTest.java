package de.farmpulse.rpsim.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.InvestmentGrant;
import de.farmpulse.rpsim.domain.InvestmentGrantObject;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
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
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-B2: investment grant (owner decisions 2026-10-05). One game day per FS25 period. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class InvestmentGrantTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired InvestmentGrantService grants;
    @Autowired AuthorityBillService bills;
    @Autowired ContractActions actions;
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
        t0 = sg.getCurrentGameTime();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(t0);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
    }

    /**
     * Facts at the current game time: the journal month {@code month} (1 = period 1 of year 1, 13 = period 1 of year 2)
     * with {@code byType} and the own vehicles {@code id:value}.
     */
    private void facts(int month, String byType, String... vehicles) {
        long t = sg.getCurrentGameTime();
        int year = 1 + (month - 1) / 12;
        int period = (month - 1) % 12 + 1;
        String doc = TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 500_000, year, period,
                "[{ \"year\": " + year + ", \"period\": " + period + ", \"byType\": { " + byType + " } }]");
        StringBuilder v = new StringBuilder("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }");
        for (String x : vehicles) {
            String[] p = x.split(":");
            v.append(", { \"uniqueId\": \"").append(p[0]).append("\", \"value\": ").append(p[1]).append(", \"condition\": 100 }");
        }
        doc = doc.replace("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }", v.toString());
        FactsSnapshot s = fx.snapshot(sg, t, 500_000, doc);
        grants.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private void day() {
        grants.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private void at(long t) {
        sg.setCurrentGameTime(t);
    }

    private List<OutboxInstruction> money() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    @Test
    void applicationRules() {
        assertThatThrownBy(() -> grants.apply(sg, InvestmentGrant.MACHINE, 5_000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("10000");
        assertThatThrownBy(() -> grants.apply(sg, "TRACTOR", 50_000)).isInstanceOf(BusinessRuleException.class);
        InvestmentGrant g = grants.apply(sg, InvestmentGrant.MACHINE, 50_000);
        assertThat(g.getStatus()).isEqualTo(InvestmentGrant.APPLIED);
        assertThat(g.getApprovalDueGameTime()).isEqualTo(t0 + 10 * DAY); // no office clerk
        assertThatThrownBy(() -> grants.apply(sg, InvestmentGrant.MACHINE, 50_000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("läuft schon");
        assertThat(grants.apply(sg, InvestmentGrant.BUILDING, 50_000).getStatus()).isEqualTo(InvestmentGrant.APPLIED);
    }

    @Test
    void onlyPurchasesAfterTheApprovalCountAndASaleInTheBindingPeriodIsRepaid() {
        InvestmentGrant g = grants.apply(sg, InvestmentGrant.MACHINE, 100_000);
        facts(1, "\"SHOP_VEHICLE_BUY\": -50000", "veh_00050:50000"); // bought before the approval
        assertThatThrownBy(() -> grants.submitProof(sg, g.getId())).isInstanceOf(BusinessRuleException.class);

        at(t0 + 10 * DAY + DAY / 4);
        facts(11, "\"SHOP_VEHICLE_BUY\": -50000", "veh_00050:50000");
        day();
        assertThat(g.getStatus()).isEqualTo(InvestmentGrant.APPROVED);
        assertThat(g.getPurchaseDeadlineGameTime()).isEqualTo(t0 + 16 * DAY); // start of the 6th month after
        assertThat(narrations()).contains("INVESTMENT_GRANT_APPROVED");
        assertThatThrownBy(() -> grants.submitProof(sg, g.getId())).hasMessageContaining("noch nichts gekauft");

        at(t0 + 10 * DAY + DAY / 2); // same month: + 80,000 bought after the approval
        facts(11, "\"SHOP_VEHICLE_BUY\": -130000", "veh_00050:50000", "veh_00077:80000");
        assertThat(g.getRecognisedSum()).isEqualTo(80_000);
        at(t0 + 11 * DAY + DAY / 4); // next month: + 20,000
        facts(12, "\"SHOP_VEHICLE_BUY\": -20000", "veh_00050:50000", "veh_00077:80000", "veh_00078:20000");
        assertThat(g.getRecognisedSum()).isEqualTo(100_000);
        assertThat(grants.objects(g)).extracting(InvestmentGrantObject::getVehicleUniqueId)
                .containsExactly("veh_00077", "veh_00078"); // veh_00050 was there at the approval

        grants.submitProof(sg, g.getId());
        assertThat(g.getStatus()).isEqualTo(InvestmentGrant.PAID);
        assertThat(g.getGrantAmount()).isEqualTo(30_000); // 30 % of 100,000
        var p = json.readTree(money().getFirst().getPayloadJson());
        assertThat(p.path("amount").asLong()).isEqualTo(30_000);
        assertThat(p.path("reason").asString()).isEqualTo("INVESTMENT_GRANT");
        assertThat(g.getBindingEndsGameTime()).isEqualTo(t0 + 35 * DAY);

        at(t0 + 13 * DAY + DAY / 4); // veh_00077 sold with 21.75 of 24 months left
        facts(14, "\"SHOP_VEHICLE_SELL\": 60000", "veh_00050:50000", "veh_00078:20000");
        ServiceCase bill = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.GRANT_REPAYMENT)).getFirst();
        assertThat(bill.getOfferAmount()).isEqualTo(21_750); // 30,000 x 80/100 x 21.75/24
        assertThat(bill.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(narrations()).contains("INVESTMENT_GRANT_REPAYMENT");

        actions.acceptCase(sg, bill.getId()); // pay by button
        assertThat(bill.getStatus()).isEqualTo(CaseStatus.SETTLED);
        var r = json.readTree(money().getLast().getPayloadJson());
        assertThat(r.path("amount").asLong()).isEqualTo(-21_750);
        assertThat(r.path("reason").asString()).isEqualTo("INVESTMENT_GRANT");
    }

    @Test
    void nothingBoughtUntilTheDeadlineExpires() {
        InvestmentGrant g = grants.apply(sg, InvestmentGrant.BUILDING, 40_000);
        at(t0 + 10 * DAY);
        facts(11, "\"SHOP_PROPERTY_BUY\": -1000");
        day();
        at(t0 + 17 * DAY);
        facts(18, "\"SHOP_VEHICLE_BUY\": -80000"); // a machine does not count for a building
        day();
        assertThat(g.getStatus()).isEqualTo(InvestmentGrant.EXPIRED);
        assertThat(narrations()).contains("INVESTMENT_GRANT_EXPIRED");
        assertThat(money()).isEmpty();
    }

    @Test
    void repaymentFormula() {
        assertThat(InvestmentGrantService.repayment(30_000, 80_000, 100_000, 22, 24)).isEqualTo(22_000);
        assertThat(InvestmentGrantService.repayment(30_000, 80_000, 100_000, 0, 24)).isZero();
        assertThat(InvestmentGrantService.grant(400_000, 300_000, props.getFormulas().getInvestmentGrant()))
                .isEqualTo(50_000); // capped
    }

    @Test
    void anUnpaidBillGetsALateFeeAndAReminder() {
        ServiceCase b = bills.create(sg, CaseKind.SOCIAL_INSURANCE_BILL,
                fx.character(sg, CharacterRole.SOCIAL_INSURANCE, CharacterCategory.MANDATORY, "Herr Brandt"),
                "Beitrag Berufsgenossenschaft 1", 1_000, "SOCIAL_INSURANCE");
        at(b.getDeadlineGameTime() + DAY / 2);
        bills.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(b.getCostAmount()).isEqualTo(10); // 1 % per started month
        assertThat(narrations()).contains("AUTHORITY_BILL_REMINDER");
    }
}
