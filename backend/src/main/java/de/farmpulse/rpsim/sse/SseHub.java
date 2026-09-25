package de.farmpulse.rpsim.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Channel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events hub (technical concept: SSE instead of polling). Event names: {@code mail}, {@code call},
 * {@code diary}, {@code state}. Events are sent after the transaction committed, so a client that reloads data on an
 * event always sees it.
 */
@Component
public class SseHub {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        send(e, "hello", Map.of("connected", true));
        return e;
    }

    public int subscribers() {
        return emitters.size();
    }

    public void broadcast(String name, Object data) {
        for (SseEmitter e : emitters) {
            send(e, name, data);
        }
    }

    private void send(SseEmitter e, String name, Object data) {
        try {
            e.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommunication(CommunicationService.CommunicationCreated ev) {
        broadcast(ev.channel() == Channel.CALL ? "call" : "mail",
                Map.of("id", ev.communicationId(), "savegameId", ev.savegameId(), "channel", ev.channel().name()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallStatus(CallService.CallStatusChanged ev) {
        broadcast("call", Map.of("id", ev.communicationId(), "savegameId", ev.savegameId(), "status", ev.status().name()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDiary(DiaryService.DiaryEntryCreated ev) {
        broadcast("diary", Map.of("id", ev.entryId(), "savegameId", ev.savegameId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFacts(BridgeEvents.FactsIngested ev) {
        broadcast("state", Map.of("savegameId", ev.savegameId(), "gameTime", ev.gameTime()));
    }

    /** Keep-alive so proxies/browsers keep the stream open. */
    @Scheduled(fixedDelay = 20000)
    public void keepAlive() {
        broadcast("ping", Map.of());
    }
}
