package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.trace.data.SpanData;

/** User Story 3: diagnostics carry trace ids (specs/010-otel-observability tasks.md T030). */
class DiagnosticsCorrelationTest {

    private static List<String> run(TripEngine trips) {
        trips.engine.receive(new TripRequested("trip-1"));
        trips.engine.receive(new HotelBooked("trip-1"));
        trips.engine.receive(new FlightUnavailable("trip-1"));
        return trips.logger.records().stream().map(RecordingLogger.Logged::message).toList();
    }

    @Test
    @DisplayName("lines written inside a unit of work carry the current span's trace and span id")
    void traceIds() {
        TelemetryAssertions telemetry = new TelemetryAssertions();
        TripEngine trips = new TripEngine(telemetry.openTelemetry(), null, null);
        run(trips);

        RecordingLogger.Logged started = trips.logger.byEvent("saga.compensation.started").get(0);
        SpanData handle = telemetry.named("saga_forge.handle").get(2);
        assertThat(started.keys()).containsEntry("traceId", handle.getTraceId());
        assertThat(trips.logger.byEvent("saga.uow.committed")).allSatisfy(line ->
                assertThat(line.keys()).containsKeys("traceId", "spanId"));
    }

    @Test
    @DisplayName("without an SDK every line is byte-for-byte as before")
    void unchangedWithoutSdk() {
        List<String> without = run(new TripEngine(null, null, null));
        List<String> noop = run(new TripEngine(OpenTelemetry.noop(), null, null));

        assertThat(noop).isEqualTo(without).noneMatch(line -> line.contains("traceId="));
    }
}
