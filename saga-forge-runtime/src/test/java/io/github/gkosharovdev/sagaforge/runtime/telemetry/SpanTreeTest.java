package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions.attribute;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInvocation;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.BookFlight;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.BookHotel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.CancelHotel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Trip;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;

/** User Story 1: the span tree of one saga handling (specs/010-otel-observability tasks.md T012). */
class SpanTreeTest {

    private static final AttributeKey<String> SAGA_TYPE = AttributeKey.stringKey("saga_forge.saga.type");
    private static final AttributeKey<String> SAGA_ID = AttributeKey.stringKey("saga_forge.saga.id");
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("saga_forge.event.type");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("saga_forge.handling.outcome");
    private static final AttributeKey<String> STEP = AttributeKey.stringKey("saga_forge.step");
    private static final AttributeKey<String> COMMAND_TYPE = AttributeKey.stringKey("saga_forge.command.type");
    private static final AttributeKey<String> COMMAND_ID = AttributeKey.stringKey("saga_forge.command.id");
    private static final AttributeKey<String> COMMAND_RESULT = AttributeKey.stringKey("saga_forge.command.result");
    private static final AttributeKey<String> DELIVERY = AttributeKey.stringKey("saga_forge.command.delivery");
    private static final AttributeKey<String> ATTEMPT_RESULT = AttributeKey.stringKey("saga_forge.attempt.result");
    private static final AttributeKey<Long> ATTEMPT_NUMBER = AttributeKey.longKey("saga_forge.attempt.number");
    private static final AttributeKey<String> CLASSIFICATION =
            AttributeKey.stringKey("saga_forge.failure.classification");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> FAILURE_MESSAGE = AttributeKey.stringKey("saga_forge.failure.message");
    private static final AttributeKey<String> ASSOCIATION = AttributeKey.stringKey("saga_forge.association.value");
    private static final AttributeKey<String> CONTENT = AttributeKey.stringKey("saga_forge.content");
    private static final AttributeKey<String> COMPENSATION_STATUS =
            AttributeKey.stringKey("saga_forge.compensation.status");
    private static final AttributeKey<String> COMPENSATION_TRIGGER =
            AttributeKey.stringKey("saga_forge.compensation.trigger");

    private final TelemetryAssertions telemetry = new TelemetryAssertions();

    private TripEngine engine(Consumer<CommandInvocation> interceptor) {
        return new TripEngine(telemetry.openTelemetry(), null, interceptor::accept);
    }

    private TripEngine engine() {
        return engine(CommandInvocation::proceed);
    }

    private static List<String> eventNames(SpanData span) {
        return span.getEvents().stream().map(EventData::getName).toList();
    }

    @Test
    @DisplayName("scenarios 1 and 2: handle → attempt → step → command under the caller's span")
    void spanTree() {
        TripEngine trips = engine();
        Span root = telemetry.openTelemetry().getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            trips.engine.receive(new TripRequested("trip-1"));
        } finally {
            root.end();
        }

        SpanData handle = telemetry.only("saga_forge.handle");
        assertThat(handle.getParentSpanId()).isEqualTo(root.getSpanContext().getSpanId());
        assertThat(handle.getTraceId()).isEqualTo(root.getSpanContext().getTraceId());
        assertThat(attribute(handle, SAGA_TYPE)).isEqualTo(TripModel.TripSaga.class.getName());
        assertThat(attribute(handle, EVENT_TYPE)).isEqualTo(TripRequested.class.getName());
        assertThat(attribute(handle, SAGA_ID)).isEqualTo("saga-1");
        assertThat(attribute(handle, OUTCOME)).isEqualTo("WAITING");
        assertThat(eventNames(handle)).contains("saga_forge.saga.started");

        SpanData attempt = telemetry.childrenOf(handle, "saga_forge.attempt").get(0);
        assertThat(attribute(attempt, ATTEMPT_RESULT)).isEqualTo("committed");
        assertThat(attribute(attempt, ATTEMPT_NUMBER)).isEqualTo(1L);
        SpanData step = telemetry.childrenOf(attempt, "saga_forge.step").get(0);
        assertThat(attribute(step, STEP)).isEqualTo(TripModel.START);
        List<SpanData> commands = telemetry.childrenOf(step, "saga_forge.command");
        assertThat(commands).extracting(span -> attribute(span, COMMAND_TYPE))
                .containsExactly(BookHotel.class.getName(), TripModel.PingTracker.class.getName());
        assertThat(commands).allSatisfy(command -> {
            assertThat(attribute(command, COMMAND_RESULT)).isEqualTo("handled");
            assertThat(attribute(command, DELIVERY)).isEqualTo("in_unit_of_work");
            assertThat(attribute(command, COMMAND_ID)).isNotBlank();
        });
    }

    @Test
    @DisplayName("scenario 4: each attempt is a span; the retries are events on handle")
    void retries() {
        int[] failures = {2};
        TripEngine trips = engine(invocation -> {
            if (invocation.command() instanceof BookFlight && failures[0]-- > 0) {
                throw new Busy();
            }
            invocation.proceed();
        });
        trips.engine.receive(new TripRequested("trip-1"));
        telemetry.reset();

        trips.engine.receive(new HotelBooked("trip-1"));

        SpanData handle = telemetry.only("saga_forge.handle");
        List<SpanData> attempts = telemetry.childrenOf(handle, "saga_forge.attempt");
        assertThat(attempts).extracting(span -> attribute(span, ATTEMPT_NUMBER)).containsExactly(1L, 2L, 3L);
        assertThat(attempts.subList(0, 2)).allSatisfy(failed -> {
            assertThat(attribute(failed, ATTEMPT_RESULT)).isEqualTo("rolled_back");
            assertThat(attribute(failed, CLASSIFICATION)).isEqualTo("TRANSIENT");
            assertThat(attribute(failed, ERROR_TYPE)).isEqualTo(Busy.class.getName());
            assertThat(failed.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        });
        assertThat(attribute(attempts.get(2), ATTEMPT_RESULT)).isEqualTo("committed");
        assertThat(eventNames(handle)).containsExactly("saga_forge.retry.scheduled", "saga_forge.retry.scheduled");
        assertThat(telemetry.named("saga_forge.command")).extracting(span -> attribute(span, COMMAND_RESULT))
                .containsExactly("failed", "failed", "handled");
    }

    @Test
    @DisplayName("scenario 5: compensation units of work are spans under handle, with their trigger")
    void compensation() {
        TripEngine trips = engine();
        trips.engine.receive(new TripRequested("trip-1"));
        trips.engine.receive(new HotelBooked("trip-1"));
        telemetry.reset();

        trips.engine.receive(new FlightUnavailable("trip-1"));

        SpanData handle = telemetry.only("saga_forge.handle");
        assertThat(attribute(handle, OUTCOME)).isEqualTo("COMPENSATED");
        assertThat(eventNames(handle)).contains("saga_forge.compensation.started", "saga_forge.saga.ended");
        SpanData compensation = telemetry.childrenOf(handle, "saga_forge.compensation").get(0);
        assertThat(attribute(compensation, STEP)).isEqualTo(TripModel.START);
        assertThat(attribute(compensation, COMPENSATION_STATUS)).isEqualTo("COMPENSATED");
        assertThat(attribute(compensation, COMPENSATION_TRIGGER)).isEqualTo("FAILURE_OUTCOME");
        assertThat(telemetry.childrenOf(compensation, "saga_forge.command"))
                .extracting(span -> attribute(span, COMMAND_TYPE)).containsExactly(CancelHotel.class.getName());
    }

    @Test
    @DisplayName("scenario 6: a failure marks handle ERROR with its type; the message only when switched on")
    void failure() {
        TripEngine trips = engine(invocation -> {
            throw new IllegalStateException("hotel down");
        });

        trips.engine.receive(new TripRequested("trip-1"));

        SpanData handle = telemetry.only("saga_forge.handle");
        assertThat(attribute(handle, OUTCOME)).isEqualTo("FAILED");
        assertThat(attribute(handle, CLASSIFICATION)).isEqualTo("UNCLASSIFIED");
        assertThat(attribute(handle, ERROR_TYPE)).isEqualTo(IllegalStateException.class.getName());
        assertThat(handle.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(telemetry.spans()).allSatisfy(span -> {
            assertThat(attribute(span, FAILURE_MESSAGE)).isNull();
            assertThat(eventNames(span)).doesNotContain("exception");
        });

        TelemetryAssertions verbose = new TelemetryAssertions();
        new TripEngine(verbose.openTelemetry(), SagaTelemetrySettings.defaults().withRecordFailureMessages(true),
                invocation -> {
                    throw new IllegalStateException("hotel down");
                }).engine.receive(new TripRequested("trip-1"));
        assertThat(verbose.named("saga_forge.command")).first().satisfies(command -> {
            assertThat(attribute(command, FAILURE_MESSAGE)).isEqualTo("hotel down");
            assertThat(eventNames(command)).contains("exception");
        });
    }

    @Test
    @DisplayName("SC-006: association values and contents only when switched on")
    void details() {
        engine().engine.receive(new TripRequested("trip-1"));
        assertThat(telemetry.spans()).allSatisfy(span -> {
            assertThat(attribute(span, ASSOCIATION)).isNull();
            assertThat(attribute(span, CONTENT)).isNull();
        });

        TelemetryAssertions verbose = new TelemetryAssertions();
        new TripEngine(verbose.openTelemetry(), SagaTelemetrySettings.defaults().withRecordAssociationValues(true)
                .withRecordContents(true), null).engine.receive(new TripRequested("trip-1"));
        SpanData handle = verbose.only("saga_forge.handle");
        assertThat(attribute(handle, ASSOCIATION)).isEqualTo("trip-1");
        assertThat(attribute(handle, CONTENT)).isEqualTo("TripRequested[tripId=trip-1]");
        assertThat(verbose.named("saga_forge.command")).extracting(span -> attribute(span, CONTENT))
                .contains("BookHotel[tripId=trip-1]");
    }

    @Test
    @DisplayName("edge cases: duplicate, unmatched and ignored events, and a command outside a saga")
    void edgeCases() {
        TripEngine trips = engine();
        trips.engine.receive(new TripRequested("trip-1"));
        trips.engine.receive(new HotelBooked("trip-1"));
        telemetry.reset();

        trips.engine.receive(new HotelBooked("trip-1"));
        trips.engine.receive(new HotelBooked("trip-404"));
        trips.engine.receive(new Viewed("trip-1"));
        trips.engine.commandGateway().send(new BookHotel("trip-x"));

        List<SpanData> handles = telemetry.named("saga_forge.handle");
        assertThat(handles).extracting(span -> attribute(span, OUTCOME)).containsExactly("DUPLICATE", "UNMATCHED");
        // A duplicate of an active instance is found by the unit of work's locked read: one attempt that ran nothing.
        assertThat(telemetry.childrenOf(handles.get(0), "saga_forge.attempt"))
                .extracting(span -> attribute(span, ATTEMPT_RESULT)).containsExactly("not_run");
        assertThat(telemetry.childrenOf(handles.get(1), "saga_forge.attempt")).isEmpty();
        assertThat(attribute(telemetry.only("saga_forge.event.ignored"), EVENT_TYPE)).isEqualTo(Viewed.class.getName());
        SpanData outside = telemetry.only("saga_forge.command");
        assertThat(outside.getParentSpanContext().isValid()).isFalse();
        assertThat(attribute(outside, SAGA_ID)).isNull();
    }

    /** No saga reacts to it. */
    @DomainEvent(aggregate = Trip.class)
    public record Viewed(@AggregateIdentifier String tripId) {
    }

    /** Busy for now: retrying may help. */
    private static final class Busy extends RuntimeException implements TransientFailure {

        Busy() {
            super("busy");
        }
    }
}
