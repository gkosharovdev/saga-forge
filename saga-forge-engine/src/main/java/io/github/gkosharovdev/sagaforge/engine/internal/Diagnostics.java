package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Every diagnostic the engine writes, one method per row of {@code contracts/diagnostics.md} (FR-038).
 *
 * <p>Each message is a fixed event name followed by {@code key=value} pairs separated by single spaces; a key
 * whose value is unknown is omitted. Messages below {@code INFO} are built lazily. Free-text values ({@code cause})
 * come last, so a reader splitting on {@code " key="} recovers every pair.</p>
 *
 * <p><strong>Not API.</strong> Public only so that {@code SagaEngine} can log its startup outcome; hosts route
 * diagnostics through {@code SagaEngine.builder().logger(...)} or a {@code System.LoggerFinder}.</p>
 *
 * <p>Immutable; thread-safe if the logger is.</p>
 */
public final class Diagnostics {

    /** What a message is about: the domain event, saga type, instance and unit of work, as far as known. */
    record Correlation(String eventType, String sagaType, String sagaId, String unitOfWorkId) {

        static Correlation of(Object event) {
            return new Correlation(event.getClass().getName(), null, null, null);
        }

        Correlation forSaga(String type) {
            return new Correlation(eventType, type, sagaId, unitOfWorkId);
        }

        Correlation instance(String id) {
            return new Correlation(eventType, sagaType, id, unitOfWorkId);
        }

        Correlation inUnitOfWork(String id) {
            return new Correlation(eventType, sagaType, sagaId, id);
        }
    }

    private final Logger logger;

    /**
     * @param logger where every diagnostic goes; must not be {@code null}
     */
    public Diagnostics(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger must not be null");
        }
        this.logger = logger;
    }

    /** {@code saga.startup.refused}: the error count, then one {@code ERROR} line per error. */
    public void startupRefused(List<String> errors) {
        logger.log(Level.ERROR, Line.of("saga.startup.refused").with("errors", errors.size()).toString());
        errors.forEach(error -> logger.log(Level.ERROR, Line.of("saga.startup.refused").with("error", error)
                .toString()));
    }

    /** {@code saga.startup.started}: the registered saga types, comma-separated, in registration order. */
    public void startupStarted(List<String> sagaTypes) {
        logger.log(Level.INFO, Line.of("saga.startup.started").with("sagaTypes", String.join(",", sagaTypes))
                .toString());
    }

    void eventIgnored(Object event) {
        logger.log(Level.DEBUG, () -> Line.of("saga.event.ignored").with("event", event.getClass().getName())
                .toString());
    }

    void eventUnmatched(Correlation about, String step) {
        logger.log(Level.DEBUG, () -> Line.of("saga.event.unmatched").event(about).saga(about).with("step", step)
                .toString());
    }

    void eventUnexpected(Correlation about, String step) {
        logger.log(Level.INFO, Line.of("saga.event.unexpected").event(about).saga(about).instance(about)
                .with("step", step).toString());
    }

    void eventDuplicateStart(Correlation about, String associationProperty, String associationValue) {
        logger.log(Level.INFO, Line.of("saga.event.duplicateStart").event(about).saga(about)
                .with("associationProperty", associationProperty).with("associationValue", associationValue)
                .toString());
    }

    void unitOfWorkCommitted(Correlation about, HandlingOutcome outcome, List<String> steps) {
        logger.log(Level.DEBUG, () -> Line.of("saga.uow.committed").event(about).saga(about).instance(about)
                .unitOfWork(about).with("outcome", outcome).with("steps", String.join(",", steps)).toString());
    }

    void unitOfWorkRolledBack(Correlation about, String step, Throwable cause) {
        logger.log(Level.WARNING, Line.of("saga.uow.rolledBack").event(about).saga(about).instance(about)
                .unitOfWork(about).with("step", step).cause(cause).toString(), cause);
    }

    void instanceFailed(Correlation about, SagaFailure failure) {
        logger.log(Level.WARNING, Line.of("saga.instance.failed").saga(about).instance(about)
                .with("step", failure.stepName()).with("classification", failure.classification())
                .with("attempts", failure.attempts()).with("cause", describe(failure)).toString());
        if (failure.classification() == FailureClassification.EXHAUSTED) {
            logger.log(Level.WARNING, Line.of("saga.uow.exhausted").saga(about).instance(about)
                    .with("step", failure.stepName()).with("attempts", failure.attempts())
                    .with("cause", failure.causeType()).toString());
        }
    }

    /** {@code saga.event.duplicate}: the event's step already completed for the instance (005 FR-006). */
    void eventDuplicate(Correlation about, String step) {
        logger.log(Level.DEBUG, () -> Line.of("saga.event.duplicate").event(about).saga(about).instance(about)
                .with("step", step).toString());
    }

    /** {@code saga.command.skipped}: a command whose handling already committed was not handled again (FR-004). */
    void commandSkipped(CommandId id) {
        logger.log(Level.INFO, Line.of("saga.command.skipped").with("sagaId", id.sagaId())
                .with("step", id.stepName()).with("command", id.commandType()).with("commandId", id.value())
                .toString());
    }

    /** {@code saga.uow.retrying}: an attempt failed transiently and the next one was claimed (005 FR-013). */
    void unitOfWorkRetrying(Correlation about, SagaFailure failure, int nextAttempt, long waitMillis) {
        logger.log(Level.INFO, Line.of("saga.uow.retrying").event(about).saga(about).instance(about)
                .unitOfWork(about).with("step", failure.stepName()).with("failedAttempt", failure.attempts())
                .with("nextAttempt", nextAttempt).with("waitMillis", waitMillis)
                .with("cause", failure.causeType()).toString());
    }

    /** {@code saga.uow.retryInterrupted}: the wait for a claimed attempt was interrupted (005 FR-014b). */
    void retryInterrupted(Correlation about, int claimedAttempt) {
        logger.log(Level.WARNING, Line.of("saga.uow.retryInterrupted").event(about).saga(about).instance(about)
                .with("claimedAttempt", claimedAttempt).toString());
    }

    /** {@code saga.compensation.started}: the instance entered {@code COMPENSATING} (006 FR-018). */
    void compensationStarted(Correlation about, CompensationTrigger trigger) {
        logger.log(Level.INFO, Line.of("saga.compensation.started").saga(about).instance(about).unitOfWork(about)
                .with("trigger", trigger.kind()).with("step", trigger.stepName())
                .with("eventType", trigger.eventType())
                .with("cause", trigger.failure() == null ? null : describe(trigger.failure())).toString());
    }

    /** {@code saga.compensation.resumed}: an event found the instance {@code COMPENSATING} (006 research §10). */
    void compensationResumed(Correlation about, String step) {
        logger.log(Level.INFO, Line.of("saga.compensation.resumed").event(about).saga(about).instance(about)
                .with("step", step).toString());
    }

    /** {@code saga.compensation.step}: one step's compensation committed, or it had nothing to compensate. */
    void compensationStep(Correlation about, String step, CompensationStatus status) {
        logger.log(Level.INFO, Line.of("saga.compensation.step").saga(about).instance(about).unitOfWork(about)
                .with("step", step).with("status", status).toString());
    }

    /** {@code saga.compensation.failed}: a compensation failed; the instance requires intervention (FR-016). */
    void compensationFailed(Correlation about, SagaFailure failure) {
        logger.log(Level.ERROR, Line.of("saga.compensation.failed").saga(about).instance(about).unitOfWork(about)
                .with("step", failure.stepName()).with("classification", failure.classification())
                .with("cause", describe(failure)).toString());
    }

    /** {@code saga.compensation.ended}: every applicable compensation committed (FR-015). */
    void compensationEnded(Correlation about) {
        logger.log(Level.INFO, Line.of("saga.compensation.ended").saga(about).instance(about).unitOfWork(about)
                .toString());
    }

    /** {@code saga.compensation.notRecorded}: a compensation outcome could not be written; it propagates. */
    void compensationNotRecorded(Correlation about, Throwable cause) {
        logger.log(Level.ERROR, Line.of("saga.compensation.notRecorded").saga(about).instance(about).cause(cause)
                .toString(), cause);
    }

    void instanceFailedNotRecorded(Correlation about, Throwable cause) {
        logger.log(Level.ERROR, Line.of("saga.instance.failedNotRecorded").saga(about).instance(about).cause(cause)
                .toString(), cause);
    }

    void unitOfWorkConflict(Correlation about) {
        logger.log(Level.WARNING, Line.of("saga.uow.conflict").event(about).saga(about).instance(about)
                .unitOfWork(about).toString());
    }

    void unitOfWorkNotStarted(Correlation about) {
        logger.log(Level.INFO, Line.of("saga.uow.notStarted").event(about).saga(about).instance(about).toString());
    }

    void outboxFailed(Correlation about, int events, Throwable cause) {
        logger.log(Level.ERROR, Line.of("saga.outbox.failed").unitOfWork(about).saga(about).instance(about)
                .with("events", events).toString(), cause);
    }

    void outboxCommittedFailed(Correlation about, Throwable cause) {
        logger.log(Level.WARNING, Line.of("saga.outbox.committedFailed").unitOfWork(about).saga(about)
                .instance(about).toString(), cause);
    }

    void eventNotHandled(Object event, Throwable cause) {
        logger.log(Level.ERROR, Line.of("saga.event.notHandled").with("event", event.getClass().getName())
                .toString(), cause);
    }

    private static String describe(SagaFailure failure) {
        return failure.message() == null ? failure.causeType() : failure.causeType() + ": " + failure.message();
    }

    /** Builds one message: the event name, then {@code key=value} pairs, skipping {@code null} values. */
    private static final class Line {

        private final StringBuilder text;

        private Line(String eventName) {
            this.text = new StringBuilder(eventName);
        }

        static Line of(String eventName) {
            return new Line(eventName);
        }

        Line with(String key, Object value) {
            if (value != null) {
                text.append(' ').append(key).append('=').append(value);
            }
            return this;
        }

        Line event(Correlation about) {
            return with("event", about.eventType());
        }

        Line saga(Correlation about) {
            return with("sagaType", about.sagaType());
        }

        Line instance(Correlation about) {
            return with("sagaId", about.sagaId());
        }

        Line unitOfWork(Correlation about) {
            return with("uow", about.unitOfWorkId());
        }

        Line cause(Throwable cause) {
            String message = cause.getMessage();
            return with("cause", message == null ? cause.getClass().getName()
                    : cause.getClass().getName() + ": " + message);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
