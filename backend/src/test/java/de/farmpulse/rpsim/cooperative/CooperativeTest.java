package de.farmpulse.rpsim.cooperative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CoopPriceYear;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.market.ForwardContractService;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.repository.CoopPriceYearRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-D7: cooperative shares, general assembly and board (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class CooperativeTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired CooperativeService coop;
    @Autowired de.farmpulse.rpsim.tablet.CalendarPlanService calendar;
    @Autowired ContractActions actions;
    @Autowired ServiceRoleService roles;
    @Autowired MarketEventEngine market;
    @Autowired ForwardContractService forwards;
    @Autowired CoopPriceYearRepository priceYears;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(12);
        sg.setCalYear(3);
        fx.snapshot(sg, 1_000_000);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setCoopAssembly(new RpsimProperties.CoopAssembly());
    }

    private JsonNode lastMoney() {
        return json.readTree(outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION).getLast()
                .getPayloadJson());
    }

    /** The next month start (one day per period); {@code period} of the new month. */
    private void month(int period) {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
        sg.setCalMonthIndex(sg.getCalMonthIndex() + 1);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalPeriod(period);
        coop.onMonth(new GameMonthPassedEvent(sg.getId(), sg.getCalMonthIndex(), sg.getCurrentGameTime()));
    }

    private void prices(int year, double mean) {
        CoopPriceYear y = new CoopPriceYear();
        y.setSavegame(sg);
        y.setCropYear(year);
        y.setFillType("WHEAT");
        y.setPriceSum(mean * 10);
        y.setSamples(10);
        y.setLastDay(0);
        priceYears.save(y);
    }

    @Test
    void sharesAreBoughtWithinTheLimitAndRepaidAtNominalAfterTheNotice() {
        coop.buy(sg, 40);
        assertThat(sg.getCoopShares()).isEqualTo(40);
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(-20_000);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("COOP_SHARES");
        assertThatThrownBy(() -> coop.buy(sg, 161)).isInstanceOf(BusinessRuleException.class);

        coop.cancel(sg, 10);
        assertThat(coop.noticed(sg)).isEqualTo(10);
        assertThatThrownBy(() -> coop.cancel(sg, 31)).isInstanceOf(BusinessRuleException.class);
        for (int i = 0; i < 11; i++) {
            month(i % 12 + 1);
        }
        assertThat(sg.getCoopShares()).isEqualTo(40); // the dividend runs until the repayment
        month(12);
        assertThat(sg.getCoopShares()).isEqualTo(30);
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(5_000);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("COOP_SHARES");
    }

    @Test
    void theDividendFollowsThePriceIndexWithinTheCap() {
        coop.buy(sg, 40);
        assertThat(coop.dividendRate(sg, 2)).isEqualTo(0.04); // no years yet
        prices(1, 200);
        prices(2, 220);
        assertThat(coop.dividendRate(sg, 2)).isEqualTo(0.044, Offset.offset(1e-9));
        month(1); // year change: 40 x 500 x 4.4 %
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(880);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("COOP_DIVIDEND");

        prices(3, 600);
        assertThat(coop.dividendRate(sg, 3)).isEqualTo(0.08); // capped
        sg.setCoopDividendBonus(0.01);
        assertThat(coop.dividendRate(sg, 2)).isEqualTo(0.054, Offset.offset(1e-9));
    }

    @Test
    void theAssemblyVotesElectsTheBoardAndTheBoardMeetsQuarterly() {
        props.getFormulas().getCoopAssembly().setTopics(List.of("GRAIN_STORE"));
        coop.buy(sg, 40);
        Character cooperative = roles.ensure(sg, CharacterRole.COOPERATIVE);
        cooperative.setTrustScore(60);
        assertThat(market.rumorAccuracy(sg)).isEqualTo(0.7);

        month(1);
        month(2);
        ServiceCase gv = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.COOP_ASSEMBLY)).getFirst();
        assertThat(gv.getReference()).isEqualTo("GRAIN_STORE");
        actions.acceptCase(sg, gv.getId()); // yes - the only votes
        assertThat(gv.getResolution()).isEqualTo("ACCEPTED");
        assertThat(sg.isCoopGrainStore()).isTrue();
        assertThat(market.rumorAccuracy(sg)).isEqualTo(0.75, Offset.offset(1e-9));
        assertThat(sg.isCoopBoard()).isTrue();
        assertThat(forwards.maxQuantity(sg)).isEqualTo(Math.round(props.getFormulas().getForwardContract().getMaxQuantity() * 1.1));

        // the calendar shows the dates of the member and of the board (an export with the FS25 calendar)
        fx.snapshot(sg, sg.getCurrentGameTime(), 1_000_000, de.farmpulse.rpsim.support.TestData.withFields(
                de.farmpulse.rpsim.support.TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 1_000_000),
                "\"calendar\": { \"period\": 2, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": 3, \"monotonicDay\": 12 }"));
        var plan = calendar.overview(sg);
        assertThat(plan.yearEvents()).extracting(e -> e.kind() + "@" + e.period())
                .contains("COOP_ASSEMBLY@2", "COOP_DIVIDEND@1", "COOP_BOARD_MEETING@1", "COOP_BOARD_MEETING@4",
                        "COOP_BOARD_MEETING@7", "COOP_BOARD_MEETING@10");
        month(3);
        assertThat(calendar.overview(sg).agenda()).extracting(e -> e.kind()).contains("COOP_BOARD_MEETING");
        month(4); // quarterly meeting
        ServiceCase meeting = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.COOP_BOARD_MEETING)).getFirst();
        actions.declineCase(sg, meeting.getId());
        assertThat(cooperative.getTrustScore()).isEqualTo(57);
        assertThat(sg.isCoopBoard()).isTrue();
        month(5);
        month(6);
        month(7);
        ServiceCase second = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.COOP_BOARD_MEETING)).getFirst();
        assertThat(second.getId()).isNotEqualTo(meeting.getId());
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(6));
        coop.onDay(new de.farmpulse.rpsim.time.GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(second.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sg.isCoopBoard()).isFalse(); // voted out after the second missed meeting
    }

    @Test
    void villageCharactersOutvoteTheFarmWithoutShares() {
        props.getFormulas().getCoopAssembly().setTopics(List.of("DIVIDEND_UP"));
        coop.buy(sg, 1);
        for (int i = 0; i < 4; i++) {
            fx.character(sg, CharacterRole.VILLAGER, de.farmpulse.rpsim.domain.CharacterCategory.DYNAMIC, "V" + i)
                    .setTrustScore(-100); // yes with 0
        }
        roles.ensure(sg, CharacterRole.COOPERATIVE).setTrustScore(-100); // every active character votes
        ServiceCase gv = coop.invite(sg).orElseThrow();
        actions.acceptCase(sg, gv.getId());
        assertThat(gv.getResolution()).isEqualTo("REJECTED");
        assertThat(gv.getQuantity()).isEqualTo(Math.round(1 * 100 / 51.0));
        assertThat(sg.getCoopDividendBonus()).isZero();
    }
}
