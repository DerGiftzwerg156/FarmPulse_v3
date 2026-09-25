package de.farmpulse.rpsim.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import(Fixtures.class)
class CallStateMachineTest {

    @Autowired Fixtures fx;
    @Autowired CallService calls;
    @Autowired CommunicationService communications;
    @Autowired ConversationService conversations;
    @Autowired TrustEventRepository trustEvents;
    @Autowired NarrationJobRepository jobs;

    Savegame sg;
    Character caller;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        caller = fx.bank(sg);
    }

    Communication ring() {
        return communications.create(new CommunicationService.Draft(sg, caller, Channel.CALL, CommunicationInitiator.CHARACTER,
                "Anruf", "Hallo?", CommunicationCategory.CREDIT, "REPLY", "LOAN", 7L, null, null, false, null));
    }

    @Test
    void ringingAcceptedCompleted() {
        Communication c = ring();
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.RINGING);
        calls.accept(sg, c.getId());
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.ACCEPTED);
        // soft time window: no server-side timeout after ACCEPTED, even after a long (game) time
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(30));
        calls.expireRinging(sg);
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.ACCEPTED);
        calls.say(sg, c.getId(), "Worum geht es?");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).anyMatch(j -> j.getEventType().equals("CALL_CONVERSATION")
                && j.getChannel() == Channel.CALL && j.getPlayerMessage().contains("Worum"));
        calls.complete(sg, c.getId());
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.COMPLETED);
    }

    @Test
    void declineCostsTrustAndKeepsTopicOpenUntilPlayerApproaches() {
        Communication c = ring();
        calls.decline(sg, c.getId());
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.DECLINED);
        assertThat(c.isOpenTopic()).isTrue();
        assertThat(c.getRelatedEntityId()).isEqualTo(7L);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(caller)).extracting(e -> e.getReason())
                .containsExactly(TrustReason.CALL_DECLINED);
        conversations.proactive(sg, caller.getId(), "Ich rufe zurück.", Channel.CALL);
        assertThat(c.isOpenTopic()).isFalse();
    }

    @Test
    void missedAfterRingTimeoutInGameTime() {
        Communication c = ring();
        long deadline = c.getRingDeadlineGameTime();
        sg.setCurrentGameTime(deadline - 1);
        assertThat(calls.expireRinging(sg)).isZero();
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.RINGING);
        sg.setCurrentGameTime(deadline);
        assertThat(calls.expireRinging(sg)).isEqualTo(1);
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.MISSED);
        assertThat(c.isOpenTopic()).isTrue();
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(caller)).extracting(e -> e.getReason())
                .containsExactly(TrustReason.CALL_MISSED);
    }

    @Test
    void pausedGameKeepsItRinging() throws Exception {
        Communication c = ring();
        // game paused: game time does not move, however long it takes in real time
        Thread.sleep(50);
        for (int i = 0; i < 5; i++) {
            calls.expireRinging(sg);
        }
        assertThat(c.getCallStatus()).isEqualTo(CallStatus.RINGING);
    }

    @Test
    void invalidTransitionsAreRejected() {
        Communication c = ring();
        assertThatThrownBy(() -> calls.complete(sg, c.getId())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> calls.say(sg, c.getId(), "x")).isInstanceOf(BusinessRuleException.class);
        calls.decline(sg, c.getId());
        assertThatThrownBy(() -> calls.accept(sg, c.getId())).isInstanceOf(BusinessRuleException.class);
    }
}
