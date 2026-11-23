package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaModel.Compensation;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Recording;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs the next compensation of a compensating instance (specs/006-saga-compensation contracts/compensation-run.md,
 * research §7, §8; FR-009, FR-011, FR-012, FR-014).
 *
 * <p>The locked instance must be {@code COMPENSATING} with a compensation left to run, else nothing runs and the
 * result is {@link Stopped} with the status found. The next compensation is the {@code NOT_ATTEMPTED} step completed
 * last. A fresh saga object with its fields injected runs the {@code @SagaCompensation} method with the persisted
 * trigger if it declares one; its commands get 005 identities scoped to the compensation method's name. Events
 * recorded by their handlers go to the outbox, never run in-process: no forward step runs while compensating. The step
 * is then marked {@code COMPENSATED} (and the instance {@code COMPENSATED} after the last one) and committed together
 * with the compensation's effects.</p>
 *
 * <p>A failure thrown by the compensation, or reported by the command gateway even if the compensation caught it,
 * rolls everything back and is {@link Failed}; a write-time conflict is {@link Conflicted}. A failure of the locked
 * read propagates after the rollback.</p>
 *
 * <p>Not thread-safe: confined to the thread that handles the triggering event. One instance per unit of work.</p>
 */
final class RunCompensation implements UnitOfWork.Work<SagaInstance, RunCompensation.Stopped> {

    /** How the compensation ended. */
    sealed interface Result {
    }

    /**
     * The compensation of {@code step} committed; {@code instance} is the instance as written. {@link #store()} runs
     * the outbox step after the lock release and returns its failures.
     */
    record Committed(String step, SagaInstance instance, String unitOfWorkId,
            Supplier<List<Throwable>> afterRelease) implements Result {

        List<Throwable> store() {
            return afterRelease.get();
        }
    }

    /** The compensation of {@code step} failed with {@code cause}; everything was rolled back. */
    record Failed(String step, Throwable cause, Correlation correlation) implements Result {
    }

    /** The write lost a concurrency conflict; everything was rolled back and the instance stays as it was. */
    record Conflicted(String unitOfWorkId) implements Result {
    }

    /** Nothing ran: the locked instance was missing or no longer compensating ({@code status}, or {@code null}). */
    record Stopped(SagaStatus status) implements Result {
    }

    private final SagaModel saga;
    private String step;

    private RunCompensation(SagaModel saga) {
        this.saga = saga;
    }

    /**
     * Runs the next compensation of {@code sagaId} in {@code unitOfWork} to its commit or rollback.
     *
     * @param unitOfWork a unit of work for the instance, from {@link UnitOfWork#forInstance}
     * @throws RuntimeException if the locked read fails; nothing was recorded
     */
    static Result next(Collaborators with, SagaModel saga, UnitOfWork unitOfWork) {
        RunCompensation work = new RunCompensation(saga);
        Correlation correlation = unitOfWork.correlation();
        return switch (unitOfWork.run(work)) {
            case UnitOfWork.Skipped<SagaInstance, Stopped> skipped -> skipped.reason();
            case UnitOfWork.Failed<SagaInstance, Stopped> failed -> {
                with.diagnostics().unitOfWorkRolledBack(correlation, work.step, failed.cause());
                yield new Failed(work.step, failed.cause(), correlation);
            }
            case UnitOfWork.Conflicted<SagaInstance, Stopped> conflicted -> {
                with.diagnostics().unitOfWorkConflict(correlation);
                yield new Conflicted(conflicted.unitOfWorkId());
            }
            case UnitOfWork.Committed<SagaInstance, Stopped> committed -> {
                SagaInstance written = committed.value();
                with.diagnostics().compensationStep(correlation, work.step,
                        written.steps().get(work.step).compensation().status());
                if (written.status() == SagaStatus.COMPENSATED) {
                    with.diagnostics().compensationEnded(correlation);
                }
                yield new Committed(work.step, written, committed.unitOfWorkId(), committed::release);
            }
        };
    }

    @Override
    public Admission<Stopped> admit(Optional<SagaInstance> locked) {
        SagaInstance instance = locked.orElse(null);
        if (instance == null || instance.status() != SagaStatus.COMPENSATING
                || EngineSagaLifecycle.remainingCompensations(instance).isEmpty()) {
            return Admission.skip(new Stopped(instance == null ? null : instance.status()));
        }
        return Admission.proceed();
    }

    @Override
    public SagaInstance perform(WorkContext context) {
        String sagaId = context.sagaId();
        SagaInstance instance = context.lifecycle().current();
        step = EngineSagaLifecycle.remainingCompensations(instance).get(0);
        CompensationTrigger trigger = instance.compensationTrigger();
        Compensation compensation = saga.compensationFor(step).orElseThrow(() -> new IllegalStateException(
                "Step " + step + " of saga instance " + sagaId + " is NOT_ATTEMPTED but " + saga.typeName()
                        + " declares no @SagaCompensation for it"));
        Object sagaObject = context.newSagaObject();
        context.running(compensation.name());
        context.recording(() -> saga.run(compensation, sagaObject, trigger));
        context.failIfReported();
        return context.lifecycle().recordCompensated(sagaId, step);
    }

    @Override
    public void recorded(Recording recording, WorkContext context) {
        Object domainEvent = recording.event();
        if (!domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            IllegalArgumentException invalid = new IllegalArgumentException(
                    domainEvent.getClass().getName() + " is not annotated @DomainEvent");
            context.reportFailure(invalid);
            throw invalid;
        }
    }
}
