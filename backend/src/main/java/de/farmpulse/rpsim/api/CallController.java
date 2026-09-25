package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.TextRequest;
import de.farmpulse.rpsim.api.Views.MessageView;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.domain.Channel;
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
@RequestMapping("/api/calls")
public class CallController {

    private final SavegameContext context;
    private final CallService calls;
    private final CommunicationRepository communications;
    private final ApiMapper mapper;

    public CallController(SavegameContext context, CallService calls, CommunicationRepository communications, ApiMapper mapper) {
        this.context = context;
        this.calls = calls;
        this.communications = communications;
        this.mapper = mapper;
    }

    /** Ringing calls and conversations in progress. */
    @GetMapping("/pending")
    @Transactional(readOnly = true)
    public List<MessageView> pending() {
        return calls.pending(context.requireActive()).stream().map(mapper::message).toList();
    }

    /** Call log (all call messages, newest first). */
    @GetMapping
    @Transactional(readOnly = true)
    public List<MessageView> history() {
        return communications.findBySavegameAndChannelOrderByGameTimeDescIdDesc(context.requireActive(), Channel.CALL)
                .stream().map(mapper::message).toList();
    }

    @PostMapping("/{id}/accept")
    @Transactional
    public MessageView accept(@PathVariable Long id) {
        return mapper.message(calls.accept(context.requireActive(), id));
    }

    @PostMapping("/{id}/decline")
    @Transactional
    public MessageView decline(@PathVariable Long id) {
        return mapper.message(calls.decline(context.requireActive(), id));
    }

    @PostMapping("/{id}/message")
    @Transactional
    public MessageView say(@PathVariable Long id, @Valid @RequestBody TextRequest r) {
        return mapper.message(calls.say(context.requireActive(), id, r.text()));
    }

    @PostMapping("/{id}/complete")
    @Transactional
    public MessageView complete(@PathVariable Long id) {
        return mapper.message(calls.complete(context.requireActive(), id));
    }
}
