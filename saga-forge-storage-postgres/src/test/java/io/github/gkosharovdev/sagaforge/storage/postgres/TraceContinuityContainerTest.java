package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * SC-001 (specs/010-otel-observability tasks.md T024): the trace context stored with each message in Postgres survives
 * a restart, so every saga unit of work of one request is in the request's trace.
 */
@Tag("container")
class TraceContinuityContainerTest {

    private final TelemetryAssertions telemetry = new TelemetryAssertions();

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    /** One process: a runtime and a transport over the shared database and the test's OpenTelemetry SDK. */
    private record Process(SagaRuntime runtime, InMemoryTransport transport) {
    }

    private Process assemble() {
        DataSource dataSource = PostgresTestSupport.newDataSource();
        JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
        InMemoryTransport transport = InMemoryTransport.builder()
                .messageStore(new PostgresMessageStore(dataSource, boundary)).transactions(boundary)
                .settings(TransportHarness.FAST).openTelemetry(telemetry.openTelemetry()).build();
        SagaRuntime runtime = SagaRuntime.builder().register(TransportAcceptanceSuite.MODEL)
                .storage(new PostgresStorageProvider(dataSource, boundary))
                .contextStore(new PostgresSagaContextStore(dataSource)).transactions(boundary)
                .dispatcher(transport.dispatcher()).outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(new PaymentService())
                        .register(new ShippingService()).register(new CardService()))
                .openTelemetry(telemetry.openTelemetry()).build();
        runtime.start();
        return new Process(runtime, transport);
    }

    @Test
    @DisplayName("events stored before a restart are delivered after it in the publishing request's trace")
    void oneTraceAcrossARestart() {
        Process before = assemble();
        Span request = telemetry.openTelemetry().getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignored = request.makeCurrent()) {
            before.runtime().eventPublisher().publish(new OrderPlaced("o-1"));
            before.runtime().eventPublisher().publish(new PaymentConfirmed("o-1"));
        } finally {
            request.end();
        }
        before.transport().stop(Duration.ofSeconds(5));

        Process after = assemble();
        after.transport().start(after.runtime(), after.runtime().commandReceiver());
        try {
            await(() -> !PostgresTransportHarness.instances(DeliverySaga.class).isEmpty(), Duration.ofSeconds(20),
                    "the delivery saga");
            await(() -> telemetry.named("saga_forge.handle").size() >= 3, Duration.ofSeconds(10), "handle spans");

            List<SpanData> sagaSpans = telemetry.spans().stream()
                    .filter(span -> span.getName().startsWith("saga_forge.")).toList();
            assertThat(sagaSpans).isNotEmpty().allSatisfy(span ->
                    assertThat(span.getTraceId()).isEqualTo(request.getSpanContext().getTraceId()));
            assertThat(new PostgresMessageStore(PostgresTestSupport.newDataSource(), new JdbcTransactionBoundary(
                    PostgresTestSupport.newDataSource())).query(MessageQuery.builder().build()))
                    .allSatisfy(message -> assertThat(message.traceContext()).containsKey("traceparent"));
        } finally {
            after.transport().stop(Duration.ofSeconds(5));
        }
    }
}
