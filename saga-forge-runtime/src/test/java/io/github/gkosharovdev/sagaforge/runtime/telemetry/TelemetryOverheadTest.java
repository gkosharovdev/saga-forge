package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * SC-004 measurement (specs/010-otel-observability tasks.md T042): the cost of telemetry over the pure in-memory
 * runtime, whose units of work take microseconds. Reported, not asserted (memory.md G-010-02). Run with
 * {@code -Dsaga.performance=true}.
 */
@EnabledIfSystemProperty(named = "saga.performance", matches = "true")
class TelemetryOverheadTest {

    private static final int SAGAS = 10_000;

    /** Accepts every span and keeps none. */
    private static final class DiscardingExporter implements SpanExporter {

        @Override
        public CompletableResultCode export(java.util.Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static long run(OpenTelemetry openTelemetry) {
        TripEngine trips = new TripEngine(openTelemetry, null, null);
        long started = System.nanoTime();
        for (int i = 0; i < SAGAS; i++) {
            String trip = "trip-" + i;
            trips.engine.receive(new TripRequested(trip));
            trips.engine.receive(new HotelBooked(trip));
            trips.engine.receive(new FlightBooked(trip));
        }
        return System.nanoTime() - started;
    }

    @Test
    @DisplayName("reports the ratio of an SDK over OpenTelemetry.noop() for 10,000 three-step sagas")
    void overhead() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingExporter())).build())
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(InMemoryMetricReader.create())
                        .build())
                .build();
        run(OpenTelemetry.noop());
        run(sdk);
        long noop = run(OpenTelemetry.noop());
        long recorded = run(sdk);
        System.out.printf("saga.telemetry.overhead sagas=%d noopMs=%d sdkMs=%d ratio=%.3f%n", SAGAS,
                TimeUnit.NANOSECONDS.toMillis(noop), TimeUnit.NANOSECONDS.toMillis(recorded),
                (double) recorded / noop);
    }
}
