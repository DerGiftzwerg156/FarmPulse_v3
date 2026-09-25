package de.farmpulse.rpsim.communication;

import java.time.Instant;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists mails and calls (one unified table) and publishes an event for the SSE live stream. */
@Service
public class CommunicationService {

    /** Published after a new character message or call (SSE: event "mail" / "call"). */
    public record CommunicationCreated(Long savegameId, Long communicationId, Channel channel) {
    }

    private final CommunicationRepository repo;
    private final ApplicationEventPublisher events;
    private final RpsimProperties props;

    public CommunicationService(CommunicationRepository repo, ApplicationEventPublisher events, RpsimProperties props) {
        this.repo = repo;
        this.events = events;
        this.props = props;
    }

    public record Draft(Savegame savegame, Character character, Channel channel, CommunicationInitiator initiatedBy,
                        String subject, String body, CommunicationCategory category, String eventType,
                        String relatedType, Long relatedId, Long threadRootId, String formLink, boolean usedFallback,
                        ToneClass toneClass) {
    }

    @Transactional
    public Communication create(Draft d) {
        Communication c = new Communication();
        c.setSavegame(d.savegame());
        c.setCharacter(d.character());
        c.setChannel(d.channel());
        c.setInitiatedBy(d.initiatedBy());
        c.setSubject(d.subject());
        c.setBody(d.body());
        c.setGameTime(d.savegame().getCurrentGameTime());
        c.setCreatedAt(Instant.now());
        c.setReadFlag(d.initiatedBy() == CommunicationInitiator.PLAYER);
        c.setCategory(d.category());
        c.setEventType(d.eventType());
        c.setRelatedEntityType(d.relatedType());
        c.setRelatedEntityId(d.relatedId());
        c.setThreadRootId(d.threadRootId());
        c.setFormLink(d.formLink());
        c.setUsedFallback(d.usedFallback());
        c.setToneClass(d.toneClass());
        boolean incomingCall = d.channel() == Channel.CALL && d.initiatedBy() == CommunicationInitiator.CHARACTER
                && d.threadRootId() == null;
        if (incomingCall) {
            c.setCallStatus(CallStatus.RINGING);
            c.setRingDeadlineGameTime(d.savegame().getCurrentGameTime()
                    + GameTime.hours(props.getFormulas().getCalls().getRingTimeoutGameMinutes() / 60.0));
        }
        repo.save(c);
        if (c.getThreadRootId() == null) {
            c.setThreadRootId(c.getId());
        }
        if (d.initiatedBy() == CommunicationInitiator.CHARACTER) {
            events.publishEvent(new CommunicationCreated(d.savegame().getId(), c.getId(), d.channel()));
        }
        return c;
    }
}
