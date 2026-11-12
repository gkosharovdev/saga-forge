package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * The in-memory transport's OpenTelemetry instrumentation ({@code specs/010-otel-observability} research §3, §4,
 * contracts/message-context.md): it injects the current trace context into each stored message, continues it in a
 * {@code CONSUMER} span {@code saga_forge.delivery} on delivery, and exposes the message-store gauges. Its own failures
 * are logged once per kind ({@code saga.telemetry.failed}) and never reach a delivery (FR-012).
 *
 * <p>Thread-safe.</p>
 */
public final class TransportInstrumentation {

    static final String SCOPE = "io.github.gkosharovdev.sagaforge";
    static final String DELIVERY = "saga_forge.delivery";

    static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    static final AttributeKey<String> MESSAGING_OPERATION = AttributeKey.stringKey("messaging.operation.type");
    static final AttributeKey<String> MESSAGE_ID = AttributeKey.stringKey("messaging.message.id");
    static final AttributeKey<String> MESSAGE_KIND = AttributeKey.stringKey("saga_forge.message.kind");
    static final AttributeKey<String> PAYLOAD_TYPE = AttributeKey.stringKey("saga_forge.payload.type");
    static final AttributeKey<Long> DELIVERY_ATTEMPT = AttributeKey.longKey("saga_forge.delivery.attempt");
    static final AttributeKey<String> MESSAGE_STATUS = AttributeKey.stringKey("saga_forge.message.status");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    static final AttributeKey<String> FAILURE_MESSAGE = AttributeKey.stringKey("saga_forge.failure.message");

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final Tracer tracer;
    private final Meter meter;
    private final TextMapPropagator propagator;
    private final boolean tracing;
    private final SagaTelemetrySettings settings;
    private final Logger logger;
    private final DoubleHistogram deliveryDuration;
    private final Set<String> failedKinds = ConcurrentHashMap.newKeySet();

    /**
     * @param openTelemetry where spans and metrics go; {@link OpenTelemetry#noop()} records nothing
     * @param settings      what is recorded
     * @param logger        where instrumentation failures are logged
     */
    public TransportInstrumentation(OpenTelemetry openTelemetry, SagaTelemetrySettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.tracing = settings.tracing();
        String version = versionOf(TransportInstrumentation.class);
        this.tracer = (settings.tracing() ? openTelemetry.getTracerProvider() : TracerProvider.noop())
                .tracerBuilder(SCOPE).setInstrumentationVersion(version).build();
        this.meter = (settings.metrics() ? openTelemetry.getMeterProvider() : MeterProvider.noop())
                .meterBuilder(SCOPE).setInstrumentationVersion(version).build();
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.deliveryDuration = meter.histogramBuilder(DELIVERY + ".duration").setUnit("s").build();
    }

    /** The current trace context as a carrier to store with a message; empty without a recording span. */
    public Map<String, String> currentContext() {
        if (!tracing || !Span.current().getSpanContext().isValid()) {
            return Map.of();
        }
        try {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(Context.current(), carrier, (target, key, value) -> target.put(key, value));
            return carrier;
        } catch (RuntimeException failure) {
            failed("inject", failure);
            return Map.of();
        }
    }

    /** Starts the {@code CONSUMER} delivery span of {@code message}, parented by its stored context, and makes it current. */
    public Delivery startDelivery(StoredMessage message) {
        long started = System.nanoTime();
        Attributes metricAttributes = Attributes.of(MESSAGING_SYSTEM, "saga_forge.inmemory", MESSAGE_KIND,
                message.kind().name().toLowerCase(java.util.Locale.ROOT), PAYLOAD_TYPE, message.payloadType());
        try {
            Context parent = message.traceContext().isEmpty() ? Context.root()
                    : propagator.extract(Context.root(), message.traceContext(), GETTER);
            Span span = tracer.spanBuilder(DELIVERY).setSpanKind(SpanKind.CONSUMER).setParent(parent)
                    .setAllAttributes(metricAttributes)
                    .setAttribute(MESSAGING_OPERATION, "process")
                    .setAttribute(MESSAGE_ID, message.messageId())
                    .setAttribute(DELIVERY_ATTEMPT, (long) message.attempts())
                    .startSpan();
            return new Delivery(span, span.makeCurrent(), started, metricAttributes);
        } catch (RuntimeException failure) {
            failed(DELIVERY, failure);
            return new Delivery(Span.getInvalid(), Scope.noop(), started, metricAttributes);
        }
    }

    /**
     * Registers the message-store gauges ({@code saga_forge.message_store.messages} per status and
     * {@code saga_forge.message_store.oldest_unconsumed.age}), reading {@code status} at most once per
     * {@code refresh}.
     *
     * @return closes the gauges
     */
    public AutoCloseable gauges(Supplier<BacklogStatus> status, Duration refresh, Clock clock) {
        try {
            CachedStatus cached = new CachedStatus(status, refresh, clock);
            AutoCloseable messages = meter.gaugeBuilder("saga_forge.message_store.messages").setUnit("{message}")
                    .buildWithCallback(measurement -> cached.get().counts().forEach((messageStatus, count) ->
                            measurement.record(count, Attributes.of(MESSAGE_STATUS, messageStatus.name()))));
            AutoCloseable age = meter.gaugeBuilder("saga_forge.message_store.oldest_unconsumed.age").setUnit("s")
                    .buildWithCallback(measurement -> measurement.record(cached.get().oldestUnconsumed()
                            .map(duration -> duration.toMillis() / 1_000.0).orElse(0.0)));
            return () -> {
                messages.close();
                age.close();
            };
        } catch (RuntimeException failure) {
            failed("gauges", failure);
            return () -> { };
        }
    }

    private void failed(String kind, RuntimeException failure) {
        if (failedKinds.add(kind)) {
            logger.log(Level.WARNING, "saga.telemetry.failed kind=" + kind + " cause=" + failure, failure);
        }
    }

    private static String versionOf(Class<?> type) {
        String version = type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    /** The figures the gauges report: counts per status and the oldest unconsumed message's age. */
    public record BacklogStatus(Map<MessageStatus, Integer> counts, java.util.Optional<Duration> oldestUnconsumed) {
    }

    /** One delivery span, current until {@link #close()}. */
    public final class Delivery implements AutoCloseable {

        private final Span span;
        private final Scope scope;
        private final long started;
        private final Attributes metricAttributes;
        private String errorType;

        private Delivery(Span span, Scope scope, long started, Attributes metricAttributes) {
            this.span = span;
            this.scope = scope;
            this.started = started;
            this.metricAttributes = metricAttributes;
        }

        /** Marks the delivery failed. */
        public void fail(Throwable failure) {
            try {
                errorType = failure.getClass().getName();
                span.setAttribute(ERROR_TYPE, errorType);
                span.setStatus(StatusCode.ERROR);
                if (settings.recordFailureMessages()) {
                    span.setAttribute(FAILURE_MESSAGE, String.valueOf(failure.getMessage()));
                    span.recordException(failure);
                }
            } catch (RuntimeException broken) {
                failed(DELIVERY, broken);
            }
        }

        @Override
        public void close() {
            try {
                scope.close();
                Attributes recorded = errorType == null ? metricAttributes
                        : metricAttributes.toBuilder().put(ERROR_TYPE, errorType).build();
                deliveryDuration.record((System.nanoTime() - started) / 1_000_000_000.0, recorded);
                span.end();
            } catch (RuntimeException failure) {
                failed(DELIVERY, failure);
            }
        }
    }

    /** Reads the status at most once per {@code refresh}. */
    private static final class CachedStatus {

        private final Supplier<BacklogStatus> source;
        private final Duration refresh;
        private final Clock clock;
        private Instant readAt;
        private BacklogStatus value;

        CachedStatus(Supplier<BacklogStatus> source, Duration refresh, Clock clock) {
            this.source = source;
            this.refresh = refresh;
            this.clock = clock;
        }

        synchronized BacklogStatus get() {
            Instant now = clock.instant();
            if (value == null || !now.isBefore(readAt.plus(refresh))) {
                try {
                    value = source.get();
                } catch (RuntimeException failure) {
                    value = value != null ? value : new BacklogStatus(Map.of(), java.util.Optional.empty());
                }
                readAt = now;
            }
            return value;
        }
    }
}
