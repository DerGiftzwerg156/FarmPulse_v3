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
        sg.setCurrentGameTime(GameTime.days(100) + GameTime.hours(8)); // day 100 -> dayOfYear 100 % 12 = 4
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
        assertThat(life.invite(sg)).isFalse(); // dayOfYear 4
        sg.setCurrentGameTime(GameTime.days(102));  // dayOfYear 6 -> every 6 days
        assertThat(life.invite(sg)).isTrue();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("season");
        assertThat(life.season(GameTime.days(0))).isEqualTo(VillageLifeService.Season.SPRING);
        assertThat(life.season(GameTime.days(11))).isEqualTo(VillageLifeService.Season.WINTER);
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
