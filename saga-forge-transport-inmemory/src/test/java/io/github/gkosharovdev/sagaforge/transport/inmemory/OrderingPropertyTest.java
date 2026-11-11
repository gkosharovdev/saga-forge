package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Ordering under failures (FR-007, FR-016, tasks.md T041): for random messages over a few keys, each failing a random
 * number of times, every message ends consumed or set aside, and per key the consumed messages were delivered
 * successfully in position order. Restarts are covered by {@code RestartTest}.
 */
class OrderingPropertyTest {

    /** One message: its key and how many times its delivery fails before succeeding. */
    record Planned(String key, int failures) {
    }

    @Provide
    Arbitrary<List<Planned>> plans() {
        Arbitrary<Planned> one = Arbitraries.of("a", "b", "c")
                .flatMap(key -> Arbitraries.integers().between(0, 4).map(failures -> new Planned(key, failures)));
        return one.list().ofMinSize(1).ofMaxSize(15);
    }

    @Property(tries = 25)
    void perKeyOrderHoldsUnderFailures(@ForAll("plans") List<Planned> plan) {
        TransportSettings settings = TransportSettings.defaults().withWorkers(3).withMaxAttempts(3)
                .withBackoff(Duration.ofMillis(1), Duration.ofMillis(2)).withPollInterval(Duration.ofMillis(10));
        Map<Object, AtomicInteger> failuresLeft = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
        List<Object> succeeded = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Unrouted> events = new ArrayList<>();
        try (TransportUnderTest t = new TransportUnderTest(settings, event -> {
            if (failuresLeft.get(event).getAndDecrement() > 0) {
                throw new IllegalStateException("planned failure");
            }
            succeeded.add(event);
            return new HandlingReport(event, List.of());
        }).start()) {
            for (Planned planned : plan) {
                Unrouted event = new Unrouted(planned.key());
                failuresLeft.put(event, new AtomicInteger(planned.failures()));
                events.add(event);
            }
            // Each event object is distinct (identity) although equal by value; map by identity below.
            events.forEach(t::publish);

            await(() -> t.log.all().size() == plan.size() && t.log.all().stream()
                    .allMatch(m -> m.status() != MessageStatus.UNCONSUMED), Duration.ofSeconds(10), "all settled");

            for (String key : List.of("a", "b", "c")) {
                List<Object> expected = t.log.all().stream()
                        .filter(m -> m.status() == MessageStatus.CONSUMED && ((Unrouted) m.payload()).orderId()
                                .equals(key))
                        .map(StoredMessage::payload).toList();
                List<Object> actual = succeeded.stream().filter(e -> ((Unrouted) e).orderId().equals(key)).toList();
                assertThat(actual).usingElementComparator((x, y) -> x == y ? 0 : 1).isEqualTo(expected);
            }
            for (int i = 0; i < plan.size(); i++) {
                MessageStatus status = t.log.all().get(i).status();
                assertThat(status).isEqualTo(plan.get(i).failures() >= 3 ? MessageStatus.SET_ASIDE
                        : MessageStatus.CONSUMED);
            }
        }
    }
}
