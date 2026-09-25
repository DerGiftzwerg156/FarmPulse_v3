package de.farmpulse.rpsim.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

/** java.net.http implementation of {@link AiHttp}. */
@Component
public class JdkAiHttp implements AiHttp {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Response postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (HttpTimeoutException e) {
            throw new AiProviderException("Zeitüberschreitung bei " + uri.getHost(), e);
        } catch (ConnectException e) {
            throw new AiProviderException("Keine Verbindung zu " + uri.getHost() + ":" + uri.getPort(), e);
        } catch (IOException e) {
            throw new AiProviderException("Netzwerkfehler: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("unterbrochen", e);
        }
    }
}
