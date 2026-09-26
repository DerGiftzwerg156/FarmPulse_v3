package de.farmpulse.rpsim.village;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VillageLifeServiceTest {

    @Autowired Fixtures fx;
    @Autowired VillageLifeService life;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCurrentGameTime(GameTime.days(100) + GameTime.hours(8)); // fallback calendar: day 100 = period 5
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Rohde");
        fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Hauke Petersen");
        fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Wiebke Nissen");
    }

    @AfterEach
    void noMoneyInstructions() {
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }

    private long count(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).filter(type::equals).count();
    }

    private void balanceAt(double daysAgo, long balance) {
        long t = sg.getCurrentGameTime() - GameTime.days(daysAgo);
        fx.snapshot(sg, t, balance, TestData.farmFacts(sg.getBridgeSavegameId(), t, balance));
    }

    @Test
    void congratulationOnClearlyPositiveCashflowTrendWithCooldown() {
        balanceAt(60, 100_000);
        balanceAt(30, 101_000);  // previous window: +1 000 over 30 months
        balanceAt(0, 400_000);   // current window: strongly positive
        assertThat(life.congratulate(sg)).isTrue();
        assertThat(count("VILLAGE_CONGRATULATION")).isEqualTo(1);
        assertThat(life.congratulate(sg)).isFalse(); // cooldown
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(20));
        balanceAt(0, 900_000);
        assertThat(life.congratulate(sg)).isTrue();
    }

    @Test
    void noCongratulationWithoutTrend() {
        balanceAt(60, 100_000);
        balanceAt(30, 100_000);
        balanceAt(0, 100_000);
        assertThat(life.congratulate(sg)).isFalse();
        assertThat(count("VILLAGE_CONGRATULATION")).isZero();
    }

    @Test
    void invitationsFollowTheFallbackCalendar() {
        assertThat(life.invite(sg)).isFalse(); // not the start of a period
        sg.setCurrentGameTime(GameTime.days(102));  // fallback (1 day per period): day 102 = period 7 -> every 6 periods
        assertThat(life.invite(sg)).isTrue();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("season");
        assertThat(life.season(sg, GameTime.days(0))).isEqualTo(VillageLifeService.Season.SPRING);
        assertThat(life.season(sg, GameTime.days(11))).isEqualTo(VillageLifeService.Season.WINTER);
    }

    /** TODO T-08: with the FS25 calendar the invitation comes on the first day of period 1 (March) and 7 (September). */
    @Test
    void invitationsFollowTheFs25Periods() {
        // period 6 (August) started on day 99, 3 days per period -> period 7 starts on day 102, period 8 on day 105
        sg.setCalMonthIndex(20L);
        sg.setCalMonthStartGameTime(GameTime.days(99));
        sg.setCalDaysPerPeriod(3);
        sg.setCalPeriod(6);
        sg.setCurrentGameTime(GameTime.days(101));
        assertThat(life.invite(sg)).isFalse();
        sg.setCurrentGameTime(GameTime.days(102));
        assertThat(life.season(sg, sg.getCurrentGameTime())).isEqualTo(VillageLifeService.Season.AUTUMN);
        assertThat(life.invite(sg)).isTrue();
        sg.setCurrentGameTime(GameTime.days(105));
        assertThat(life.invite(sg)).isFalse();
        assertThat(VillageLifeService.seasonOfPeriod(10)).isEqualTo(VillageLifeService.Season.WINTER);
        assertThat(VillageLifeService.seasonOfPeriod(1)).isEqualTo(VillageLifeService.Season.SPRING);
    }

    @Test
    void gossipHasMinimalFactsAndCooldown() {
        assertThat(life.gossip(sg)).isTrue();
        String facts = jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson();
        assertThat(facts).contains("aboutName");
        assertThat(life.gossip(sg)).isFalse();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(3));
        assertThat(life.gossip(sg)).isTrue();
    }
}
