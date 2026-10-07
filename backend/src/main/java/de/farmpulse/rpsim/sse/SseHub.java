package de.farmpulse.rpsim.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events hub (technical concept: SSE instead of polling). Event names: {@code mail}, {@code call},
 * {@code diary}, {@code state}, {@code notice}. Events are sent after the transaction committed, so a client that reloads data on an
 * event always sees it.
 * <p>
 * Technical review 10/2026, Phase 1.5/1.7 (R-3, R-7):
 * <ul>
 *   <li>Every client has its own bounded queue and its own sender thread. {@link #broadcast} only queues, so a slow or
 *       stuck client never holds the committing thread (e.g. the bridge cycle). A client whose queue is full is
 *       disconnected; the app reconnects by itself and reloads everything ({@code hello}).</li>
 *   <li>When the application shuts down all streams are completed. An open stream ({@code SseEmitter} without
 *       timeout) otherwise counts as an active request, and Tomcat's graceful shutdown waits 30 s for it.</li>
 * </ul>
 * Only the sender thread touches its emitter: {@code send} and {@code complete} share one lock in
 * {@code ResponseBodyEmitter}, a {@code complete} from outside would wait for a stuck {@code send}.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    /** Events a client may lag behind before it is disconnected. */
    static final int QUEUE_CAPACITY = 100;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    public SseEmitter subscribe() {
        return subscribe(new SseEmitter(0L));
    }

    /** Registers {@code emitter} as a client (tests pass an emitter that blocks like a stuck network). */
    SseEmitter subscribe(SseEmitter emitter) {
        Client c = new Client(emitter, "sse-" + ids.incrementAndGet());
        clients.add(c);
        emitter.onCompletion(c::close);
        emitter.onTimeout(c::close);
        emitter.onError(t -> c.close());
        c.offer(new Event("hello", Map.of("connected", true)));
        c.thread.start();
        return emitter;
    }

    public int subscribers() {
        return clients.size();
    }

    /** Queues the event for every client; never waits for a client. */
    public void broadcast(String name, Object data) {
        Event event = new Event(name, data);
        for (Client c : clients) {
            c.offer(event);
        }
    }

    /** R-7: completes every stream before the web server shuts down gracefully. */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        clients.forEach(Client::close);
    }

    private record Event(String name, Object data) {
    }

    /** Marks the end of a client's queue. */
    private static final Event CLOSE = new Event(null, null);

    private final class Client implements Runnable {
        private final SseEmitter emitter;
        private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final Thread thread;
        private volatile boolean closed;

        Client(SseEmitter emitter, String name) {
            this.emitter = emitter;
            this.thread = new Thread(this, name);
            this.thread.setDaemon(true);
        }

        void offer(Event event) {
            if (closed) {
                return;
            }
            if (!queue.offer(event)) {
                log.info("SSE client {} lags {} events behind, disconnecting it (it reconnects by itself)",
                        thread.getName(), QUEUE_CAPACITY);
                close();
            }
        }

        /** Ends the client: the sender thread drops what is left, completes the stream and stops. */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            clients.remove(this);
            while (!queue.offer(CLOSE)) {
                queue.clear();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Event e = queue.take();
                    if (e == CLOSE || closed) {
                        break;
                    }
                    emitter.send(SseEmitter.event().name(e.name()).data(e.data()));
                }
            } catch (IOException | IllegalStateException ex) {
                // client gone or stream already completed
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                closed = true;
                clients.remove(this);
                try {
                    emitter.complete();
                } catch (RuntimeException ex) {
                    // already completed by the container
                }
            }
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotice(de.farmpulse.rpsim.notice.NoticeService.NoticeChanged ev) {
        broadcast("notice", Map.of("id", ev.noticeId(), "savegameId", ev.savegameId()));
    }

    /** Keep-alive so proxies/browsers keep the stream open (only queues, so Spring's scheduler thread never waits). */
    @Scheduled(fixedDelay = 20000)
    public void keepAlive() {
        broadcast("ping", Map.of());
    }
}
