package de.farmpulse.rpsim.ai;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Mock HTTP client: records requests and returns a canned response (no real API call in tests). */
class MockAiHttp implements AiHttp {

    record Request(URI uri, Map<String, String> headers, String body) {
    }

    final List<Request> requests = new ArrayList<>();
    Supplier<Response> next = () -> new Response(200, "{}");

    @Override
    public Response postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
        requests.add(new Request(uri, headers, body));
        return next.get();
    }
}
