package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Trip;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;

/** FR-012 and SC-007: faulty instrumentation never changes a saga (specs/010-otel-observability tasks.md T004). */
class FaultyInstrumentationTest {

    /** An event whose {@code toString} throws. */
    @DomainEvent(aggregate = Trip.class)
    public record Odd(@AggregateIdentifier String tripId) {

        @Override
        public String toString() {
            throw new IllegalStateException("toString broke");
        }
    }

    @Saga
    public static final class OddSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "tripId")
        public void started(Odd event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "tripId")
        public void ended(HotelBooked event) {
        }
    }

    /** An OpenTelemetry whose tracer throws from every {@code spanBuilder} call. */
    private static final class ThrowingOpenTelemetry implements OpenTelemetry {

        @Override
        public TracerProvider getTracerProvider() {
            Tracer tracer = name -> {
                throw new IllegalStateException("tracer broke");
            };
            return new TracerProvider() {
                @Override
                public Tracer get(String name) {
                    return tracer;
                }

                @Override
                public Tracer get(String name, String version) {
                    return tracer;
                }

                @Override
                public TracerBuilder tracerBuilder(String name) {
                    return new TracerBuilder() {
                        @Override
                        public TracerBuilder setSchemaUrl(String schemaUrl) {
                            return this;
                        }

                        @Override
                        public TracerBuilder setInstrumentationVersion(String version) {
                            return this;
                        }

                        @Override
                        public Tracer build() {
                            return tracer;
                        }
                    };
                }
            };
        }

        @Override
        public ContextPropagators getPropagators() {
            return ContextPropagators.noop();
        }
    }

    private static SagaRuntime engine(OpenTelemetry openTelemetry, SagaTelemetrySettings settings,
            RecordingLogger logger) {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaRuntime engine = SagaRuntime.builder().register(OddSaga.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore()).transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .openTelemetry(openTelemetry).telemetry(settings).logger(logger).build();
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("a content whose toString throws is skipped and logged once")
    void throwingToString() {
        RecordingLogger logger = new RecordingLogger();
        TelemetryAssertions telemetry = new TelemetryAssertions();
        SagaRuntime engine = engine(telemetry.openTelemetry(),
                SagaTelemetrySettings.defaults().withRecordContents(true), logger);

        assertThat(engine.receive(new Odd("trip-1")).results().get(0).outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(engine.receive(new Odd("trip-2")).results().get(0).outcome()).isEqualTo(HandlingOutcome.WAITING);

        assertThat(logger.byEvent("saga.telemetry.failed")).hasSize(1);
        assertThat(telemetry.named("saga_forge.handle")).hasSize(2);
    }

    @Test
    @DisplayName("a tracer that throws is skipped and logged once; the saga runs as without telemetry")
    void throwingTracer() {
        RecordingLogger logger = new RecordingLogger();
        SagaRuntime engine = engine(new ThrowingOpenTelemetry(), SagaTelemetrySettings.defaults(), logger);

        assertThat(engine.receive(new Odd("trip-1")).results().get(0).outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(engine.receive(new HotelBooked("trip-1")).results().get(0).outcome())
                .isEqualTo(HandlingOutcome.COMPLETED);

        assertThat(logger.byEvent("saga.telemetry.failed")).extracting(logged -> logged.keys().get("kind"))
                .doesNotHaveDuplicates().contains("saga_forge.handle");
    }

    @Test
    @DisplayName("a disabled tracer and meter record nothing")
    void switchedOff() {
        TelemetryAssertions telemetry = new TelemetryAssertions();
        SagaRuntime engine = engine(telemetry.openTelemetry(),
                SagaTelemetrySettings.defaults().withTracing(false).withMetrics(false), new RecordingLogger());

        engine.receive(new Odd("trip-1"));

        assertThat(telemetry.spans()).isEmpty();
        assertThat(telemetry.collectMetrics()).isEmpty();
    }
}
