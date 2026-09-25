package de.farmpulse.rpsim.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live updates: pushes new mails, calls and diary entries - no polling needed. */
@RestController
public class EventStreamController {

    private final SseHub hub;

    public EventStreamController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
