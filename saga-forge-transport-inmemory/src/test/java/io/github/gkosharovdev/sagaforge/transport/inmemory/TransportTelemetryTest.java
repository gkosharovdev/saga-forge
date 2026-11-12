package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions.attribute;
import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Trace context through the message store and the transport's spans, with the runtime and the transport assembled by
 * hand around one {@code OpenTelemetry} (specs/010-otel-observability tasks.md T014, T040; FR-005, FR-006, FR-015).
 */
class TransportTelemetryTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final AttributeKey<String> SAGA_TYPE = AttributeKey.stringKey("saga_forge.saga.type");
    private static final AttributeKey<String> MESSAGE_KIND = AttributeKey.stringKey("saga_forge.message.kind");
    private static final AttributeKey<String> COMMAND_DELIVERY = AttributeKey.stringKey("saga_forge.command.delivery");
    private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");

    private final TelemetryAssertions telemetry = new TelemetryAssertions();
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemoryMessageStore log = new InMemoryMessageStore();
    private final CardService cards = new CardService();
    private final InMemoryTransport transport;
    private final SagaRuntime runtime;

    TransportTelemetryTest() {
        // As the README's plain-Java setup: one OpenTelemetry for the transport and the runtime (FR-015).
        transport = InMemoryTransport.builder().messageStore(log).transactions(boundary)
                .settings(TransportHarness.FAST).openTelemetry(telemetry.openTelemetry()).build();
        runtime = SagaRuntime.builder().register(TransportAcceptanceSuite.MODEL).storage(storage)
                .contextStore(new InMemorySagaContextStore()).transactions(boundary)
                .dispatcher(transport.dispatcher()).outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(new PaymentService())
                        .register(new ShippingService()).register(cards))
                .openTelemetry(telemetry.openTelemetry())
                .build();
        runtime.start();
        transport.start(runtime, runtime.commandReceiver());
    }

    @AfterEach
    void stop() {
        transport.stop(Duration.ofSeconds(5));
    }

    private boolean completed(Class<?> sagaType) {
        return storage.committed().all().stream().anyMatch(instance -> instance.sagaType().equals(sagaType.getName())
                && instance.status() == SagaStatus.COMPLETED);
    }

    private boolean running(Class<?> sagaType) {
        return storage.committed().all().stream().anyMatch(instance -> instance.sagaType().equals(sagaType.getName()));
    }

    private SagaFlow underRoot(Runnable work) {
        Span root = telemetry.openTelemetry().getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            work.run();
        } finally {
            root.end();
        }
        return new SagaFlow(root.getSpanContext().getTraceId());
    }

    private record SagaFlow(String traceId) {
    }

    @Test
    @DisplayName("FR-005/FR-006: events stored and delivered later continue the publishing trace across units of work")
    void oneTraceAcrossTheStore() {
        SagaFlow flow = underRoot(() -> {
            runtime.eventPublisher().publish(new OrderPlaced("o-1"));
            runtime.eventPublisher().publish(new PaymentConfirmed("o-1"));
        });

        await(() -> running(DeliverySaga.class), WAIT, "the delivery saga");
        await(() -> telemetry.named("saga_forge.handle").size() >= 3, WAIT, "three handle spans");

        List<SpanData> sagaSpans = telemetry.spans().stream()
                .filter(span -> span.getName().startsWith("saga_forge.")).toList();
        assertThat(sagaSpans).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(flow.traceId()));
        SpanData deliveryHandle = telemetry.named("saga_forge.handle").stream()
                .filter(span -> DeliverySaga.class.getName().equals(attribute(span, SAGA_TYPE))).findFirst()
                .orElseThrow();
        SpanData delivery = telemetry.parentOf(deliveryHandle).orElseThrow();
        assertThat(delivery.getName()).isEqualTo("saga_forge.delivery");
        assertThat(delivery.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(attribute(delivery, MESSAGING_SYSTEM)).isEqualTo("saga_forge.inmemory");
        assertThat(telemetry.parentOf(delivery).orElseThrow().getName())
                .as("the event was stored in FulfilmentSaga's attempt").isEqualTo("saga_forge.attempt");
        assertThat(log.all()).allSatisfy(message -> assertThat(message.traceContext()).containsKey("traceparent"));
        assertThat(completed(FulfilmentSaga.class)).isTrue();
    }

    @Test
    @DisplayName("an outbox command is handled in a command span under its delivery, in the sender's trace")
    void outboxCommand() {
        SagaFlow flow = underRoot(() -> runtime.eventPublisher().publish(new InvoiceIssued("i-1")));

        await(() -> completed(BillingSaga.class), WAIT, "the billing saga");
        await(() -> telemetry.named("saga_forge.handle").size() >= 2, WAIT, "two handle spans");

        SpanData command = telemetry.named("saga_forge.command").stream()
                .filter(span -> "outbox".equals(attribute(span, COMMAND_DELIVERY))).findFirst().orElseThrow();
        SpanData delivery = telemetry.parentOf(command).orElseThrow();
        assertThat(delivery.getName()).isEqualTo("saga_forge.delivery");
        assertThat(attribute(delivery, MESSAGE_KIND)).isEqualTo("command");
        assertThat(command.getTraceId()).isEqualTo(flow.traceId());
    }

    @Test
    @DisplayName("a failed delivery is an ERROR delivery span; the redelivery continues the same trace")
    void failedDelivery() {
        cards.failNext(1);
        SagaFlow flow = underRoot(() -> runtime.eventPublisher().publish(new InvoiceIssued("i-2")));

        await(() -> completed(BillingSaga.class), WAIT, "the billing saga");

        List<SpanData> commandDeliveries = telemetry.named("saga_forge.delivery").stream()
                .filter(span -> "command".equals(attribute(span, MESSAGE_KIND))).toList();
        assertThat(commandDeliveries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(commandDeliveries.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(commandDeliveries).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(flow.traceId()));
    }

    @Test
    @DisplayName("a message stored without a trace starts a new one on delivery")
    void noContext() {
        runtime.eventPublisher().publish(new OrderPlaced("o-2"));

        await(() -> !telemetry.named("saga_forge.handle").isEmpty(), WAIT, "a handle span");

        assertThat(log.all().get(0).traceContext()).isEmpty();
        SpanData delivery = telemetry.only("saga_forge.delivery");
        assertThat(delivery.getParentSpanContext().isValid()).isFalse();
    }

    @Test
    @DisplayName("FR-015: the hand-assembled runtime and transport record saga and delivery metrics")
    void metrics() {
        runtime.eventPublisher().publish(new InvoiceIssued("i-3"));
        await(() -> completed(BillingSaga.class), WAIT, "the billing saga");

        telemetry.collectMetrics();
        assertThat(telemetry.metric("saga_forge.delivery.duration")).isPresent();
        assertThat(telemetry.metric("saga_forge.handle.duration")).isPresent();
        assertThat(telemetry.counter("saga_forge.saga.ended", io.opentelemetry.api.common.Attributes.of(SAGA_TYPE,
                BillingSaga.class.getName()))).isEqualTo(1);
    }
}
