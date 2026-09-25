package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.TextRequest;
import de.farmpulse.rpsim.api.Views.MessageView;
import de.farmpulse.rpsim.api.Views.ThreadView;
import de.farmpulse.rpsim.communication.ConversationService;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mails")
public class MailController {

    private final SavegameContext context;
    private final CommunicationRepository communications;
    private final ConversationService conversations;
    private final ApiMapper mapper;

    public MailController(SavegameContext context, CommunicationRepository communications, ConversationService conversations,
                          ApiMapper mapper) {
        this.context = context;
        this.communications = communications;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    /** All mails (newest first); the frontend groups them by threadRootId. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<MessageView> list() {
        Savegame sg = context.requireActive();
        return communications.findBySavegameAndChannelOrderByGameTimeDescIdDesc(sg, Channel.MAIL).stream()
                .map(mapper::message).toList();
    }

    @GetMapping("/{id}")
    @Transactional
    public ThreadView get(@PathVariable Long id) {
        Communication c = conversations.get(context.requireActive(), id);
        c.setReadFlag(true);
        return new ThreadView(mapper.message(c), conversations.thread(c).stream().map(mapper::message).toList());
    }

    @PostMapping("/{id}/reply")
    @Transactional
    public MessageView reply(@PathVariable Long id, @Valid @RequestBody TextRequest r) {
        return mapper.message(conversations.reply(context.requireActive(), id, r.text()));
    }
}
