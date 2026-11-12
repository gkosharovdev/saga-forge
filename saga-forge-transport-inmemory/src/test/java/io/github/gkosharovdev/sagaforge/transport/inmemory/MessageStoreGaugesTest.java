package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MutableClock;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.DoublePointData;

/** User Story 2: the message-store gauges (specs/010-otel-observability tasks.md T026, FR-010). */
class MessageStoreGaugesTest {

    private static final Instant T0 = Instant.parse("2026-11-12T09:00:00Z");
    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("saga_forge.message.status");

    private final TelemetryAssertions telemetry = new TelemetryAssertions();
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final InMemoryMessageStore log = new InMemoryMessageStore();
    private final MutableClock clock = new MutableClock(T0);

    private void append(String id) {
        LocalTransaction tx = boundary.begin();
        log.append(tx, List.of(new NewMessage(id, MessageKind.EVENT, OrderPlaced.class.getName(), new OrderPlaced(id),
                null, "k-" + id, null, null, T0)));
        tx.commit();
    }

    private double gauge(String name, Attributes match) {
        return telemetry.collectMetrics().stream().filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getDoubleGaugeData().getPoints().stream())
                .filter(point -> match.asMap().entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(point.getAttributes().get(entry.getKey()))))
                .mapToDouble(DoublePointData::getValue).findFirst().orElse(-1);
    }

    @Test
    @DisplayName("the gauges report the operator endpoint's figures, cached per poll interval, until stop")
    void gauges() {
        append("a");
        append("b");
        InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(boundary)
                .settings(TransportHarness.FAST.withPollInterval(Duration.ofSeconds(30))).clock(clock)
                .openTelemetry(telemetry.openTelemetry()).build();
        transport.start(event -> {
            throw new IllegalStateException("not delivered in this test");
        }, null);
        clock.advance(Duration.ofSeconds(5));

        assertThat(gauge("saga_forge.message_store.messages", Attributes.of(STATUS, "UNCONSUMED"))).isEqualTo(2);
        assertThat(gauge("saga_forge.message_store.oldest_unconsumed.age", Attributes.empty())).isEqualTo(5.0);
        MessageStoreQueries.Status status = transport.queries().status();
        assertThat(status.counts().get(io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus.UNCONSUMED))
                .isEqualTo(2);

        append("c");
        assertThat(gauge("saga_forge.message_store.messages", Attributes.of(STATUS, "UNCONSUMED")))
                .as("cached for the poll interval").isEqualTo(2);
        clock.advance(Duration.ofSeconds(30));
        assertThat(gauge("saga_forge.message_store.messages", Attributes.of(STATUS, "UNCONSUMED"))).isEqualTo(3);

        transport.stop(Duration.ofSeconds(5));
        assertThat(gauge("saga_forge.message_store.messages", Attributes.of(STATUS, "UNCONSUMED"))).isEqualTo(-1);
    }
}
