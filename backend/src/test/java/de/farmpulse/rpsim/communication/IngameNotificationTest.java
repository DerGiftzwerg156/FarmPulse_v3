package de.farmpulse.rpsim.communication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-21: new mails and incoming calls as in-game notification. */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class IngameNotificationTest {

    @Autowired Fixtures fx;
    @Autowired CommunicationService communications;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired RpsimProperties props;

    Savegame sg;
    Character bank;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        bank = fx.bank(sg);
    }

    @AfterEach
    void restore() {
        props.getBridge().setIngameNotifications(true);
    }

    private void incoming(Channel channel, String subject, CommunicationInitiator by) {
        communications.create(new CommunicationService.Draft(sg, bank, channel, by, subject, "Text",
                CommunicationCategory.GENERAL, "REPLY", null, null, null, null, false, null));
    }

    private List<OutboxInstruction> notifications() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.NOTIFICATION)
                .toList();
    }

    @Test
    void newMailAndIncomingCallAreAnnouncedInTheGame() {
        incoming(Channel.MAIL, "Ihr Kreditantrag", CommunicationInitiator.CHARACTER);
        incoming(Channel.CALL, null, CommunicationInitiator.CHARACTER);
        assertThat(notifications()).extracting(OutboxInstruction::getPayloadJson).satisfiesExactly(
                p -> assertThat(p).contains("FarmPulse: Neue Mail von Frau Berger – Ihr Kreditantrag").contains("\"INFO\"")
                        .contains("\"expiresAtGameTime\":" + (sg.getCurrentGameTime() + GameTime.hours(2))),
                p -> assertThat(p).contains("FarmPulse: Frau Berger ruft an"));
        assertThat(notifications().get(0).getRelatedEntityType()).isEqualTo(IngameNotificationService.RELATED);
    }

    @Test
    void ownMessagesUnlinkedSavegamesAndTheSwitchSuppressIt() {
        incoming(Channel.MAIL, "Antwort", CommunicationInitiator.PLAYER);
        assertThat(notifications()).isEmpty();
        props.getBridge().setIngameNotifications(false);
        incoming(Channel.MAIL, "Aus", CommunicationInitiator.CHARACTER);
        assertThat(notifications()).isEmpty();
        props.getBridge().setIngameNotifications(true);
        sg.setLinkedAt(null);
        incoming(Channel.MAIL, "Nicht verknüpft", CommunicationInitiator.CHARACTER);
        assertThat(notifications()).isEmpty();
    }

    @Test
    void longSubjectsAreShortened() {
        incoming(Channel.MAIL, "x".repeat(300), CommunicationInitiator.CHARACTER);
        String payload = notifications().get(0).getPayloadJson();
        assertThat(payload).contains("…");
        assertThat(payload.length()).isLessThan(300);
    }
}
