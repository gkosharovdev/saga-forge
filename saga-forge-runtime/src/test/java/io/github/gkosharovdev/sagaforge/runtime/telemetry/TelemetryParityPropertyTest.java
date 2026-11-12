package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.trace.data.SpanData;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 37 (.kiro/specs/saga-forge-framework/design.md, specs/010-otel-observability tasks.md T003): for any
 * sequence of events over two trips, a runtime built without telemetry, one with {@code OpenTelemetry.noop()} and one
 * with an SDK give the same outcomes, stored instances and handled-command identities; every exported span is ended
 * and its parent, if any, is an exported span of the same trace.
 */
class TelemetryParityPropertyTest {

    private record Observed(List<HandlingOutcome> outcomes, List<Optional<SagaInstance>> instances,
            List<CommandId> handled) {
    }

    @Provide
    Arbitrary<List<Object>> events() {
        Arbitrary<String> trips = Arbitraries.of("trip-1", "trip-2");
        Arbitrary<Object> event = Arbitraries.integers().between(0, 3).flatMap(kind -> trips.map(trip -> switch (kind) {
            case 0 -> new TripRequested(trip);
            case 1 -> new HotelBooked(trip);
            case 2 -> new FlightBooked(trip);
            default -> new FlightUnavailable(trip);
        }));
        return event.list().ofMinSize(1).ofMaxSize(8);
    }

    private static Observed run(OpenTelemetry openTelemetry, List<Object> events) {
        TripEngine trips = new TripEngine(openTelemetry, null, null);
        List<HandlingOutcome> outcomes = new ArrayList<>();
        List<String> sagaIds = new ArrayList<>();
        for (Object event : events) {
            for (SagaHandlingResult result : trips.engine.receive(event).results()) {
                outcomes.add(result.outcome());
                if (result.sagaId() != null && !sagaIds.contains(result.sagaId())) {
                    sagaIds.add(result.sagaId());
                }
            }
        }
        List<CommandId> handled = new ArrayList<>(trips.desk.identities());
        handled.addAll(trips.tracker.identities());
        return new Observed(outcomes, sagaIds.stream().map(id -> trips.storage.committed().findById(id)).toList(),
                handled);
    }

    @Property(tries = 100)
    void telemetryChangesNothing(@ForAll("events") List<Object> events) {
        TelemetryAssertions telemetry = new TelemetryAssertions();

        Observed without = run(null, events);
        Observed noop = run(OpenTelemetry.noop(), events);
        Observed recorded = run(telemetry.openTelemetry(), events);

        assertThat(noop).isEqualTo(without);
        assertThat(recorded).isEqualTo(without);
        List<SpanData> spans = telemetry.spans();
        Set<String> ids = spans.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.hasEnded()).isTrue();
            if (span.getParentSpanContext().isValid()) {
                assertThat(ids).contains(span.getParentSpanId());
            }
        });
    }
}
