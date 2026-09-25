package de.farmpulse.rpsim.communication;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import de.farmpulse.rpsim.tone.ToneClassifier;
import de.farmpulse.rpsim.tone.ToneTrustService;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Call state machine (technical concept "Anruf-Zustandsautomat"):
 * <pre>
 * RINGING -> ACCEPTED (soft time window, NO backend timeout afterwards) -> COMPLETED
 *         -> DECLINED (trust malus, the player has to call back actively)
 *         -> MISSED   (ring timeout without a click)
 * </pre>
 * The ring timeout runs in GAME time: if the game is paused, the phone keeps ringing.
 */
@Service
public class CallService {

    /** Published on every state change (SSE event "call"). */
    public record CallStatusChanged(Long savegameId, Long communicationId, CallStatus status) {
    }

    private final CommunicationRepository communications;
    private final CommunicationService communicationService;
    private final SavegameRepository savegames;
    private final ConversationService conversations;
    private final TrustScoreService trust;
    private final ToneClassifier classifier;
    private final ToneTrustService toneTrust;
    private final NarrationRequestService narration;
    private final ApplicationEventPublisher events;
    private final RpsimProperties props;

    public CallService(CommunicationRepository communications, CommunicationService communicationService,
                       SavegameRepository savegames, ConversationService conversations, TrustScoreService trust,
                       ToneClassifier classifier, ToneTrustService toneTrust, NarrationRequestService narration,
                       ApplicationEventPublisher events, RpsimProperties props) {
        this.communications = communications;
        this.communicationService = communicationService;
        this.savegames = savegames;
        this.conversations = conversations;
        this.trust = trust;
        this.classifier = classifier;
        this.toneTrust = toneTrust;
        this.narration = narration;
        this.events = events;
        this.props = props;
    }

    private Communication call(Savegame sg, Long id) {
        Communication c = conversations.get(sg, id);
        if (c.getChannel() != Channel.CALL || c.getCallStatus() == null) {
            throw new BusinessRuleException("NOT_A_CALL", "Das ist kein Anruf.");
        }
        return c;
    }

    private void transition(Communication c, CallStatus from, CallStatus to) {
        if (c.getCallStatus() != from) {
            throw new BusinessRuleException("INVALID_CALL_STATE",
                    "Anruf ist im Zustand " + c.getCallStatus() + " – " + to + " nicht möglich.");
        }
        c.setCallStatus(to);
        c.setReadFlag(true);
        events.publishEvent(new CallStatusChanged(c.getSavegame().getId(), c.getId(), to));
    }

    @Transactional
    public Communication accept(Savegame sg, Long id) {
        Communication c = call(sg, id);
        transition(c, CallStatus.RINGING, CallStatus.ACCEPTED);
        return c;
    }

    @Transactional
    public Communication decline(Savegame sg, Long id) {
        Communication c = call(sg, id);
        transition(c, CallStatus.RINGING, CallStatus.DECLINED);
        c.setOpenTopic(true); // stays open until the player approaches the character (relatedEntityId keeps the topic)
        if (c.getCharacter() != null) {
            trust.recordEvent(c.getCharacter(), props.getFormulas().getTrust().getCallDeclined(), TrustReason.CALL_DECLINED,
                    "Anruf abgelehnt");
        }
        return c;
    }

    /** Player speaks during an accepted call; the character answers via the narration pipeline. */
    @Transactional
    public Communication say(Savegame sg, Long id, String text) {
        Communication c = call(sg, id);
        if (c.getCallStatus() != CallStatus.ACCEPTED) {
            throw new BusinessRuleException("INVALID_CALL_STATE", "Das Gespräch läuft nicht.");
        }
        ToneClass tone = classifier.classify(text).tone();
        Communication own = communicationService.create(new CommunicationService.Draft(sg, c.getCharacter(), Channel.CALL,
                CommunicationInitiator.PLAYER, "Gespräch", text, CommunicationCategory.PLAYER_MESSAGE, null, null, null,
                c.getThreadRootId(), null, false, tone));
        toneTrust.apply(c.getCharacter(), tone);
        narration.request(sg, NarrationEventType.CALL_CONVERSATION).from(c.getCharacter()).channel(Channel.CALL)
                .playerMessage(text).thread(c.getThreadRootId())
                .facts(NarrationFacts.builder().put("topic", c.getSubject()).build())
                .category(c.getCategory()).related(c.getRelatedEntityType(), c.getRelatedEntityId()).submit();
        return own;
    }

    @Transactional
    public Communication complete(Savegame sg, Long id) {
        Communication c = call(sg, id);
        transition(c, CallStatus.ACCEPTED, CallStatus.COMPLETED);
        return c;
    }

    /** Ring timeout in game time. */
    @EventListener
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent e) {
        expireRinging(savegames.findById(e.savegameId()).orElseThrow());
    }

    @Transactional
    public int expireRinging(Savegame sg) {
        int n = 0;
        for (Communication c : communications.findBySavegameAndChannelAndCallStatus(sg, Channel.CALL, CallStatus.RINGING)) {
            if (c.getRingDeadlineGameTime() != null && c.getRingDeadlineGameTime() <= sg.getCurrentGameTime()) {
                transition(c, CallStatus.RINGING, CallStatus.MISSED);
                c.setReadFlag(false);
                c.setOpenTopic(true);
                if (c.getCharacter() != null) {
                    trust.recordEvent(c.getCharacter(), props.getFormulas().getTrust().getCallMissed(),
                            TrustReason.CALL_MISSED, "Anruf verpasst");
                }
                n++;
            }
        }
        return n;
    }

    public List<Communication> pending(Savegame sg) {
        List<Communication> ringing = communications.findBySavegameAndChannelAndCallStatus(sg, Channel.CALL, CallStatus.RINGING);
        List<Communication> active = communications.findBySavegameAndChannelAndCallStatus(sg, Channel.CALL, CallStatus.ACCEPTED);
        java.util.ArrayList<Communication> all = new java.util.ArrayList<>(ringing);
        all.addAll(active);
        return all;
    }
}
