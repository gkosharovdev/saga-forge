package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineDurationException;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.internal.DeadlineModel.Declaration;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/**
 * Starts and cancels the deadlines of one unit of work as its steps complete (specs/017-saga-deadlines
 * contracts/runtime-deadlines.md "Starting, replacing, cancelling").
 *
 * <p>After each completed step: every open deadline whose {@code cancelledBy} lists the step is cancelled, then every
 * deadline the step declares is started (replacing one open under the same name). A started deadline is due at
 * {@code clock + duration}, truncated to milliseconds, and its id is {@code <sagaId>:deadline:<name>:<unitOfWorkId>}.
 * A timeout step's own deadline is {@linkplain #expire expired} before the step runs. Closing every open deadline
 * when the instance leaves {@code RUNNING} is the lifecycle's, so it covers every write.</p>
 *
 * <p>Not thread-safe: confined to the thread of its unit of work.</p>
 */
final class DeadlinePlan {

    private final SagaModel saga;
    private final String sagaId;
    private final String unitOfWorkId;
    private final Supplier<Instant> clock;
    private final Supplier<SagaContext> context;

    /**
     * @param context the instance's {@code SagaContext}, read for a {@code durationFrom} after the step ran
     */
    DeadlinePlan(SagaModel saga, String sagaId, String unitOfWorkId, Supplier<Instant> clock,
            Supplier<SagaContext> context) {
        this.saga = saga;
        this.sagaId = sagaId;
        this.unitOfWorkId = unitOfWorkId;
        this.clock = clock;
        this.context = context;
    }

    /**
     * Applies the deadlines of {@code stepName}, which just completed, to {@code lifecycle}.
     *
     * @throws DeadlineDurationException if a {@code durationFrom} attribute is missing or holds no ISO-8601 duration
     */
    void stepCompleted(String stepName, EngineSagaLifecycle lifecycle) {
        DeadlineModel deadlines = saga.deadlines();
        if (deadlines.isEmpty()) {
            return;
        }
        deadlines.cancelledBy(stepName).forEach(lifecycle::cancelDeadline);
        for (Declaration declaration : deadlines.startedBy(stepName)) {
            start(declaration, lifecycle);
        }
    }

    /** Closes the deadline {@code name} because its timeout step is about to run; its message is not cancelled. */
    void expire(String name, EngineSagaLifecycle lifecycle) {
        lifecycle.expireDeadline(name);
    }

    private void start(Declaration declaration, EngineSagaLifecycle lifecycle) {
        Duration duration = declaration.fixed().orElseGet(() -> fromContext(declaration));
        Instant now = clock.get();
        Instant dueAt = now.plus(duration).truncatedTo(ChronoUnit.MILLIS);
        String deadlineId = sagaId + ":deadline:" + declaration.name() + ":" + unitOfWorkId;
        DeadlineReached reached = new DeadlineReached(saga.typeName(), sagaId, declaration.name(), deadlineId, dueAt,
                declaration.startedBy());
        lifecycle.openDeadline(declaration.name(), new OpenDeadline(deadlineId, dueAt, declaration.startedBy(), now),
                ScheduledMessage.deadline(reached));
    }

    private Duration fromContext(Declaration declaration) {
        String attribute = declaration.durationFrom();
        String value;
        try {
            value = context.get().getState(attribute, String.class).orElseThrow(() -> invalid(declaration,
                    "holds no value", null));
        } catch (SagaStateTypeMismatchException notText) {
            throw invalid(declaration, "does not hold a String", notText);
        }
        try {
            Duration duration = Duration.parse(value.strip());
            if (duration.isNegative()) {
                throw invalid(declaration, "holds the negative duration '" + value + "'", null);
            }
            return duration;
        } catch (DateTimeParseException notIso) {
            throw invalid(declaration, "holds '" + value + "', which is not an ISO-8601 duration", notIso);
        }
    }

    private DeadlineDurationException invalid(Declaration declaration, String problem, Throwable cause) {
        String message = "@Saga class " + saga.type().getSimpleName() + ": deadline '" + declaration.name()
                + "' started by step '" + declaration.startedBy() + "' takes its duration from SagaContext attribute '"
                + declaration.durationFrom() + "', which " + problem + " (saga instance " + sagaId + ")";
        return cause == null ? new DeadlineDurationException(message) : new DeadlineDurationException(message, cause);
    }
}
