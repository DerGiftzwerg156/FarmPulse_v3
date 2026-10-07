package de.farmpulse.rpsim.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Technical review 10/2026, Phase 1.5/1.7 (R-3, R-7): broadcasting never waits for a client, a client that lags too
 * far behind is disconnected, and closing the hub completes every stream.
 */
class SseHubTest {

    /** Records sent events; optionally blocks every send like a client whose network stopped reading. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch unblock;

        RecordingEmitter(CountDownLatch unblock) {
            super(0L);
            this.unblock = unblock;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (unblock != null) {
                try {
                    unblock.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            StringBuilder text = new StringBuilder();
            builder.build().forEach(d -> text.append(d.getData()));
            sent.add(text.toString());
        }

        @Override
        public void complete() {
            completed.countDown();
        }
    }

    @Test
    void aStuckClientNeitherHoldsTheBroadcastNorStaysForever() throws Exception {
        SseHub hub = new SseHub();
        CountDownLatch stuck = new CountDownLatch(1);
        RecordingEmitter slow = (RecordingEmitter) hub.subscribe(new RecordingEmitter(stuck));
        RecordingEmitter fast = (RecordingEmitter) hub.subscribe(new RecordingEmitter(null));

        long broadcasting = 0;
        for (int i = 0; i < SseHub.QUEUE_CAPACITY + 10; i++) {
            long start = System.nanoTime();
            hub.broadcast("state", Map.of("n", i));
            broadcasting += System.nanoTime() - start;
            int expected = i + 2; // hello + events so far: the fast client keeps up, the stuck one cannot
            waitFor(() -> fast.sent.size() == expected);
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(broadcasting)).isLessThan(1000); // never waited for the stuck client

        assertThat(hub.subscribers()).isEqualTo(1); // the stuck client was dropped, the other one stays
        stuck.countDown();
        assertThat(slow.completed.await(5, TimeUnit.SECONDS)).as("stuck client completed").isTrue();
        waitFor(() -> fast.sent.size() == SseHub.QUEUE_CAPACITY + 11);
        assertThat(fast.sent).hasSize(SseHub.QUEUE_CAPACITY + 11); // hello + every event, in order
        assertThat(fast.sent.getFirst()).contains("hello");
        assertThat(fast.sent.getLast()).contains("n=" + (SseHub.QUEUE_CAPACITY + 9));
    }

    @Test
    void closingTheHubCompletesEveryStream() throws Exception {
        SseHub hub = new SseHub();
        RecordingEmitter a = (RecordingEmitter) hub.subscribe(new RecordingEmitter(null));
        RecordingEmitter b = (RecordingEmitter) hub.subscribe(new RecordingEmitter(null));

        hub.closeAll();

        assertThat(a.completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(b.completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(hub.subscribers()).isZero();
    }

    static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
