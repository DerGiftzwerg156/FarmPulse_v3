package de.farmpulse.rpsim.communication;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.employee.SatisfactionService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.tone.ToneClassifier;
import de.farmpulse.rpsim.tone.ToneTrustService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free player text (mail replies, proactive messages, call conversation). The text itself never moves numbers: it is
 * classified deterministically (tone -> capped trust event), stored, and answered by the character via the
 * narration pipeline inside {@code <spieler_nachricht>}.
 */
@Service
public class ConversationService {

    private final CommunicationRepository communications;
    private final CommunicationService communicationService;
    private final CharacterRepository characters;
    private final EmployeeRepository employees;
    private final SatisfactionService satisfaction;
    private final ToneClassifier classifier;
    private final ToneTrustService toneTrust;
    private final NarrationRequestService narration;
    private final RpsimProperties props;

    public ConversationService(CommunicationRepository communications, CommunicationService communicationService,
                               CharacterRepository characters, EmployeeRepository employees,
                               SatisfactionService satisfaction, ToneClassifier classifier, ToneTrustService toneTrust,
                               NarrationRequestService narration, RpsimProperties props) {
        this.communications = communications;
        this.communicationService = communicationService;
        this.characters = characters;
        this.employees = employees;
        this.satisfaction = satisfaction;
        this.classifier = classifier;
        this.toneTrust = toneTrust;
        this.narration = narration;
        this.props = props;
    }

    public Communication get(Savegame sg, Long id) {
        return communications.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("communication " + id));
    }

    public List<Communication> thread(Communication c) {
        return communications.findByThreadRootIdOrderByIdAsc(c.getThreadRootId() == null ? c.getId() : c.getThreadRootId());
    }

    private Communication storePlayerText(Savegame sg, Character c, Channel channel, String text, Long threadRoot,
                                          ToneClass tone, String subject) {
        return communicationService.create(new CommunicationService.Draft(sg, c, channel, CommunicationInitiator.PLAYER,
                subject, text, CommunicationCategory.PLAYER_MESSAGE, null, null, null, threadRoot, null, false, tone));
    }

    private void employeeAppreciation(Character c) {
        employees.findByCharacter(c).filter(e -> e.getStatus() == EmployeeStatus.ACTIVE).ifPresent(satisfaction::conversation);
    }

    /** Free reply to a character mail. */
    @Transactional
    public Communication reply(Savegame sg, Long mailId, String text) {
        Communication mail = get(sg, mailId);
        if (mail.getChannel() != Channel.MAIL) {
            throw new BusinessRuleException("NOT_A_MAIL", "Nur Mails können beantwortet werden.");
        }
        Character c = mail.getCharacter();
        if (c == null || c.getStatus() == CharacterStatus.TERMINATED) {
            throw new BusinessRuleException("NO_RECIPIENT", "Diese Nachricht kann nicht beantwortet werden.");
        }
        ToneClass tone = classifier.classify(text).tone();
        Long root = mail.getThreadRootId() == null ? mail.getId() : mail.getThreadRootId();
        Communication own = storePlayerText(sg, c, Channel.MAIL, text, root, tone, "Re: " + mail.getSubject());
        toneTrust.apply(c, tone);
        employeeAppreciation(c);
        narration.request(sg, NarrationEventType.REPLY).from(c).playerMessage(text).thread(root)
                .facts(NarrationFacts.builder().put("previousSubject", mail.getSubject()).build())
                .category(mail.getCategory()).related(mail.getRelatedEntityType(), mail.getRelatedEntityId()).submit();
        return own;
    }

    public record ProactiveResult(Communication message, boolean pacingActive) {
    }

    /**
     * "Nachricht verfassen": the pacing cooldown per character only suppresses a new trust event (no trust farming by
     * spam) - the character always answers normally.
     */
    @Transactional
    public ProactiveResult proactive(Savegame sg, Long characterId, String text, Channel channel) {
        Character c = characters.findById(characterId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("character " + characterId));
        if (c.getStatus() == CharacterStatus.TERMINATED) {
            throw new BusinessRuleException("CHARACTER_GONE", "Dieser Charakter ist nicht mehr im Dorf.");
        }
        long now = sg.getCurrentGameTime();
        boolean pacing = c.getLastPacedMessageGameTime() != null
                && GameTime.toDays(now - c.getLastPacedMessageGameTime()) < props.getFormulas().getMessages().getPacingCooldownDays();
        ToneClass tone = classifier.classify(text).tone();
        Channel ch = channel == null ? Channel.MAIL : channel;
        Communication own = storePlayerText(sg, c, ch, text, null, tone, ch == Channel.CALL ? "Anruf" : "Nachricht");
        if (ch == Channel.CALL) {
            own.setCallStatus(CallStatus.ACCEPTED);
        }
        if (!pacing) {
            toneTrust.apply(c, tone);
            c.setLastPacedMessageGameTime(now);
        }
        employeeAppreciation(c);
        // approaching the character resolves topics left open by a declined/missed call
        communications.findBySavegameAndCharacterOrderByIdDesc(sg, c).stream().filter(Communication::isOpenTopic)
                .forEach(x -> x.setOpenTopic(false));
        narration.request(sg, ch == Channel.CALL ? NarrationEventType.CALL_CONVERSATION : NarrationEventType.REPLY).from(c)
                .playerMessage(text).channel(ch).thread(own.getId()).category(CommunicationCategory.PLAYER_MESSAGE).submit();
        return new ProactiveResult(own, pacing);
    }
}
