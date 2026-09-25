package de.farmpulse.rpsim.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Minimal HTTP abstraction for the REST providers - mocked in tests (no real API call in the test suite). */
public interface AiHttp {

    record Response(int status, String body) {
    }

    Response postJson(URI uri, Map<String, String> headers, String body, Duration timeout);
}
