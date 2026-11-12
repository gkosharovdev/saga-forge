package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Tracker;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TravelDesk;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Trip;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.opentelemetry.api.OpenTelemetry;

/** A runtime over {@code TripModel} with deterministic ids, for the telemetry tests (010). */
final class TripEngine {

    final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    final TravelDesk desk = new TravelDesk();
    final Tracker tracker = new Tracker();
    final RecordingLogger logger = new RecordingLogger();
    final SagaRuntime engine;

    /**
     * @param openTelemetry the SDK, or {@code null} for a runtime built without {@code openTelemetry(...)}
     * @param settings      the telemetry settings, or {@code null} for the defaults
     * @param interceptor   the command interceptor, or {@code null} for none
     */
    TripEngine(OpenTelemetry openTelemetry, SagaTelemetrySettings settings, CommandInterceptor interceptor) {
        AtomicInteger sagas = new AtomicInteger();
        AtomicInteger units = new AtomicInteger();
        SagaRuntime.Builder builder = SagaRuntime.builder()
                .register(TripSaga.class, TravelDesk.class, Tracker.class, Trip.class)
                .storage(storage).contextStore(new InMemorySagaContextStore()).transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(CapturingOutbox.inTransaction(boundary).supportingCommands())
                .handlerInstances(new MapHandlerInstanceProvider().register(desk).register(tracker))
                .clock(Clock.fixed(Instant.parse("2026-11-12T09:00:00Z"), ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagas.incrementAndGet())
                .unitOfWorkIds(() -> "uow-" + units.incrementAndGet())
                .retryPause(wait -> { })
                .logger(logger);
        if (openTelemetry != null) {
            builder.openTelemetry(openTelemetry);
        }
        if (settings != null) {
            builder.telemetry(settings);
        }
        if (interceptor != null) {
            builder.commandInterceptor(interceptor);
        }
        engine = builder.build();
        engine.start();
    }
}
