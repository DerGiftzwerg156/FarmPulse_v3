package de.farmpulse.rpsim.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** DoD AP-6.2: a new Communication reaches a real SSE client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Fixtures.class)
class SseStreamIntegrationTest {

    @LocalServerPort int port;
    @Autowired SseHub hub;
    @Autowired CommunicationService communications;
    @Autowired DiaryService diary;
    @Autowired Fixtures fx;
    @Autowired TransactionTemplate tx;

    @Test
    void clientReceivesMailCallAndDiaryEvents() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/stream"))
                .header("accept", "text/event-stream").build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        List<String> eventNames = new ArrayList<>();
        CompletableFuture<Void> readTask = CompletableFuture.runAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        synchronized (eventNames) {
                            eventNames.add(line.substring(6).trim());
                        }
                        if (eventNames.containsAll(List.of("mail", "call", "diary"))) {
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (hub.subscribers() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        tx.executeWithoutResult(s -> {
            Savegame sg = fx.savegame();
            var bank = fx.bank(sg);
            communications.create(new CommunicationService.Draft(sg, bank, Channel.MAIL, CommunicationInitiator.CHARACTER,
                    "S", "B", CommunicationCategory.GENERAL, "REPLY", null, null, null, null, false, null));
            communications.create(new CommunicationService.Draft(sg, bank, Channel.CALL, CommunicationInitiator.CHARACTER,
                    "S", "B", CommunicationCategory.GENERAL, "REPLY", null, null, null, null, false, null));
            diary.addAuto(sg, "TEST", "Titel", "Text", null, null);
        });
        readTask.get(10, TimeUnit.SECONDS);
        assertThat(eventNames).startsWith("hello").contains("mail", "call", "diary");
    }
}
