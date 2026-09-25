package de.farmpulse.rpsim.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.support.TestCharacters;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TrustScoreServiceTest {

    @Autowired TrustScoreService trust;
    @Autowired SavegameRepository savegames;
    @Autowired CharacterRepository characters;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = savegames.save(TestData.activeSavegame("trust_" + System.nanoTime()));
        bank = characters.save(TestCharacters.of(sg, CharacterRole.BANK_ADVISOR, CharacterCategory.MANDATORY, "Frau Berger"));
    }

    @Test
    void eventsAccumulate() {
        trust.recordEvent(bank.getId(), 5, TrustReason.ON_TIME_PAYMENT);
        trust.recordEvent(bank.getId(), -2, TrustReason.TONE_RUDE);
        assertThat(trust.getCurrentTrust(bank.getId())).isEqualTo(3);
    }

    @Test
    void upperCapIsNeverExceeded() {
        for (int i = 0; i < 50; i++) {
            trust.recordEvent(bank.getId(), 10, TrustReason.PROMISE_KEPT);
        }
        assertThat(trust.getCurrentTrust(bank.getId())).isEqualTo(100);
    }

    @Test
    void lowerCapIsNeverExceeded() {
        for (int i = 0; i < 50; i++) {
            trust.recordEvent(bank.getId(), -10, TrustReason.PROMISE_BROKEN);
        }
        assertThat(trust.getCurrentTrust(bank.getId())).isEqualTo(-100);
    }

    @Test
    void noDecayWithinGracePeriod() {
        trust.recordEvent(bank.getId(), 20, TrustReason.PROMISE_KEPT);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(10));
        assertThat(trust.getCurrentTrust(bank.getId())).isEqualTo(20);
    }

    @Test
    void decayMovesTowardsNeutralOverSimulatedTime() {
        trust.recordEvent(bank.getId(), 20, TrustReason.PROMISE_KEPT);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(20)); // 10 idle days beyond grace * 0.5
        assertThat(trust.getCurrentTrust(bank.getId())).isCloseTo(15, within(1e-9));
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(1000));
        assertThat(trust.getCurrentTrust(bank.getId())).isEqualTo(0); // never overshoots neutral
    }

    @Test
    void negativeScoreDecaysUpwards() {
        trust.recordEvent(bank.getId(), -20, TrustReason.PROMISE_BROKEN);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(30));
        assertThat(trust.getCurrentTrust(bank.getId())).isCloseTo(-10, within(1e-9));
    }

    @Test
    void cachedScoreMatchesReplayOfTheLog() {
        trust.recordEvent(bank.getId(), 30, TrustReason.PROMISE_KEPT);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(25));
        trust.recordEvent(bank.getId(), -4, TrustReason.CALL_DECLINED);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(2));
        trust.recordEvent(bank.getId(), 90, TrustReason.PROMISE_KEPT);
        assertThat(trust.replay(bank)).isCloseTo(bank.getTrustScore(), within(1e-9));
        assertThat(bank.getTrustScore()).isEqualTo(100);
    }
}
