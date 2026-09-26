package de.farmpulse.rpsim.communication;

import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-21: new mails and incoming calls are shown in the game (NOTIFICATION instruction ->
 * {@code g_currentMission:addIngameNotification(FSBaseMission.INGAME_NOTIFICATION_INFO, text)}, pattern of
 * FS25_MarketDynamics), so the player does not have to look at the browser. Switch: {@code rpsim.bridge.ingame-notifications}.
 */
@Service
public class IngameNotificationService {

    public static final String RELATED = "COMMUNICATION";
    static final int MAX_TEXT = 120;

    private final CommunicationRepository communications;
    private final OutboxService outbox;
    private final RpsimProperties props;

    public IngameNotificationService(CommunicationRepository communications, OutboxService outbox, RpsimProperties props) {
        this.communications = communications;
        this.outbox = outbox;
        this.props = props;
    }

    @EventListener
    @Transactional
    public void onCommunication(CommunicationService.CommunicationCreated e) {
        if (!props.getBridge().isIngameNotifications()) {
            return;
        }
        Communication c = communications.findById(e.communicationId()).orElse(null);
        if (c == null || c.getSavegame().getLinkedAt() == null) {
            return; // not linked to an FS25 savegame (yet): nobody reads the bridge
        }
        String text = text(c);
        if (text == null) {
            return;
        }
        Savegame sg = c.getSavegame();
        outbox.notification(sg, text, "INFO",
                sg.getCurrentGameTime() + GameTime.hours(props.getBridge().getNotificationMaxAgeHours()),
                new Related(RELATED, c.getId()));
    }

    /** Short German text; null for messages that need no hint (replies within a call). */
    static String text(Communication c) {
        String from = c.getCharacter() != null ? c.getCharacter().getName() : "FarmPulse";
        String text;
        if (c.getChannel() == Channel.CALL) {
            if (c.getCallStatus() != CallStatus.RINGING) {
                return null;
            }
            text = "FarmPulse: " + from + " ruft an";
        } else {
            text = "FarmPulse: Neue Mail von " + from + (c.getSubject() == null || c.getSubject().isBlank() ? ""
                    : " – " + c.getSubject());
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
