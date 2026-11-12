package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInvocation;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.BookFlight;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

/** User Story 2: saga metrics (specs/010-otel-observability tasks.md T025). */
class SagaMetricsTest {

    private static final AttributeKey<String> SAGA_TYPE = AttributeKey.stringKey("saga_forge.saga.type");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("saga_forge.saga.outcome");
    private static final AttributeKey<String> HANDLING = AttributeKey.stringKey("saga_forge.handling.outcome");
    private static final AttributeKey<String> STEP = AttributeKey.stringKey("saga_forge.step");
    private static final Attributes TRIPS = Attributes.of(SAGA_TYPE, TripSaga.class.getName());

    /** {@code orders} trips, every fifth one compensated, the rest completed. */
    private static TelemetryAssertions run(int orders, SagaTelemetrySettings settings) {
        TelemetryAssertions telemetry = new TelemetryAssertions();
        TripEngine trips = new TripEngine(telemetry.openTelemetry(), settings, null);
        for (int i = 0; i < orders; i++) {
            String trip = "trip-" + i;
            trips.engine.receive(new TripRequested(trip));
            trips.engine.receive(new HotelBooked(trip));
            trips.engine.receive(i % 5 == 4 ? new FlightUnavailable(trip) : new FlightBooked(trip));
        }
        return telemetry;
    }

    @Test
    @DisplayName("scenario 1: starts and terminal outcomes per saga type and outcome")
    void outcomes() {
        TelemetryAssertions telemetry = run(10, null);

        assertThat(telemetry.counter("saga_forge.saga.started", TRIPS)).isEqualTo(10);
        assertThat(telemetry.counter("saga_forge.saga.ended", TRIPS.toBuilder()
                .put(OUTCOME, "completed:" + TripModel.END).build())).isEqualTo(8);
        assertThat(telemetry.counter("saga_forge.saga.ended", TRIPS.toBuilder().put(OUTCOME, "compensated").build()))
                .isEqualTo(2);
        assertThat(telemetry.counter("saga_forge.event.handled", TRIPS.toBuilder().put(HANDLING, "WAITING").build()))
                .isEqualTo(20);
        assertThat(telemetry.histogramCount("saga_forge.step.duration", Attributes.of(STEP, TripModel.START)))
                .isEqualTo(10);
        assertThat(telemetry.histogramCount("saga_forge.handle.duration", TRIPS)).isEqualTo(30);
        assertThat(telemetry.metric("saga_forge.step.duration").orElseThrow().getUnit()).isEqualTo("s");
    }

    @Test
    @DisplayName("scenarios 2 and 4: retries, exhaustion and handling outcomes of odd events")
    void retriesAndOddEvents() {
        TelemetryAssertions telemetry = new TelemetryAssertions();
        TripEngine trips = new TripEngine(telemetry.openTelemetry(), null, (CommandInvocation invocation) -> {
            if (invocation.command() instanceof BookFlight flight && flight.tripId().equals("trip-bad")) {
                throw new Busy();
            }
            invocation.proceed();
        });
        trips.engine.receive(new TripRequested("trip-bad"));
        trips.engine.receive(new HotelBooked("trip-bad"));
        trips.engine.receive(new HotelBooked("trip-404"));
        trips.engine.receive(new TripRequested("trip-bad"));

        Attributes hotel = TRIPS.toBuilder().put(STEP, TripModel.HOTEL).build();
        assertThat(telemetry.counter("saga_forge.retry.scheduled", hotel)).isEqualTo(2);
        assertThat(telemetry.counter("saga_forge.retry.exhausted", hotel)).isEqualTo(1);
        assertThat(telemetry.counter("saga_forge.event.handled", TRIPS.toBuilder().put(HANDLING, "UNMATCHED").build()))
                .isEqualTo(1);
        assertThat(telemetry.counter("saga_forge.event.handled", TRIPS.toBuilder().put(HANDLING, "DUPLICATE_START")
                .build())).isEqualTo(0);
    }

    @Test
    @DisplayName("SC-003 and FR-009: series do not grow with traffic, and no id reaches a metric")
    void boundedCardinality() {
        assertThat(run(1_000, null).series()).isEqualTo(run(10, null).series());
        assertThat(run(10, SagaTelemetrySettings.defaults().withRecordAssociationValues(true).withRecordContents(true))
                .metricAttributeKeys())
                .doesNotContain("saga_forge.saga.id", "saga_forge.command.id", "saga_forge.unit_of_work.id",
                        "saga_forge.attempt.number", "saga_forge.association.value", "saga_forge.content");
    }

    @Test
    @DisplayName("metrics off: no metric; tracing off: no span, metrics still recorded")
    void switches() {
        TelemetryAssertions noMetrics = run(2, SagaTelemetrySettings.defaults().withMetrics(false));
        assertThat(noMetrics.collectMetrics()).isEmpty();
        assertThat(noMetrics.spans()).isNotEmpty();

        TelemetryAssertions noTracing = run(2, SagaTelemetrySettings.defaults().withTracing(false));
        assertThat(noTracing.spans()).isEmpty();
        assertThat(noTracing.counter("saga_forge.saga.started", TRIPS)).isEqualTo(2);
    }

    /** Busy for now: retrying may help. */
    private static final class Busy extends RuntimeException implements TransientFailure {

        Busy() {
            super("busy");
        }
    }
}
