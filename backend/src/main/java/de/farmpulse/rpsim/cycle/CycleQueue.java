package de.farmpulse.rpsim.cycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.CycleEvent;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CycleEventRepository;
import de.farmpulse.rpsim.repository.CycleStepRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Technical review 10/2026, Phase 1.2/1.3: durable, ordered work queue of the bridge cycle. A bridge read stores its
 * data and enqueues the resulting events in one transaction, so nothing that was read can get lost; the queue is then
 * drained listener by listener ({@link CycleDispatcher}).
 */
@Component
public class CycleQueue {

    /** Only our own records are ever turned back into objects. */
    private static final String OWN_PACKAGE = "de.farmpulse.rpsim.";

    private final CycleEventRepository events;
    private final CycleStepRepository steps;
    private final JsonMapper json;

    public CycleQueue(CycleEventRepository events, CycleStepRepository steps, JsonMapper json) {
        this.events = events;
        this.steps = steps;
        this.json = json;
    }

    /** Enqueues an event; joins the transaction of the read that caused it (and requires one). */
    @Transactional(propagation = Propagation.MANDATORY)
    public CycleEvent enqueue(Savegame sg, Record payload) {
        CycleEvent e = new CycleEvent();
        e.setSavegame(sg);
        e.setEventType(payload.getClass().getName());
        e.setPayloadJson(json.writeValueAsString(payload));
        e.setCreatedAt(Instant.now());
        return events.save(e);
    }

    public boolean hasWork(Long savegameId) {
        return events.existsBySavegameId(savegameId);
    }

    public List<Long> savegamesWithWork() {
        return events.savegamesWithWork();
    }

    public Optional<CycleEvent> next(Long savegameId) {
        return events.findFirstBySavegameIdOrderByIdAsc(savegameId);
    }

    public Object payload(CycleEvent e) {
        return json.readValue(e.getPayloadJson(), type(e.getEventType()));
    }

    /** Stores the progress of a long-running event (the game-time work of a snapshot). */
    @Transactional
    public void update(Long eventId, Record payload) {
        CycleEvent e = events.findById(eventId).orElseThrow();
        e.setPayloadJson(json.writeValueAsString(payload));
    }

    /** Every listener is done or skipped: the event and its journal are removed. */
    @Transactional
    public void complete(Long eventId) {
        steps.deleteByCycleEventId(eventId);
        events.deleteById(eventId);
    }

    static Class<?> type(String name) {
        if (!name.startsWith(OWN_PACKAGE)) {
            throw new IllegalStateException("Unexpected cycle event type " + name);
        }
        try {
            Class<?> c = Class.forName(name);
            if (!c.isRecord()) {
                throw new IllegalStateException("Cycle event type is no record: " + name);
            }
            return c;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unknown cycle event type " + name, e);
        }
    }
}
