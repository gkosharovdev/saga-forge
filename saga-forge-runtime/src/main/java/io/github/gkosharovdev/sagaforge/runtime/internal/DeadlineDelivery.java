package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Trigger;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaModel.Step;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.Optional;

/**
 * Delivers one {@link DeadlineReached} to the instance that started the deadline (specs/017-saga-deadlines
 * contracts/runtime-deadlines.md "Delivery of {@code DeadlineReached}").
 *
 * <p>Routed by saga type and saga id, never by association. Under the instance lock, {@link RunSteps} admits the
 * delivery only if the instance is {@code RUNNING} and holds the deadline open under the delivered id; then the
 * {@code @DeadlineExpired} step runs in an ordinary unit of work through the {@link AttemptLoop} (retry, failure
 * classification and compensation as for any step). Otherwise the result is {@link HandlingOutcome#IGNORED} and nothing
 * is written.</p>
 *
 * <p>A saga type this process does not know, or one without a timeout step for the deadline, means the processes run
 * different models: {@link EventHandlingException}, so the transport keeps the message.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are.</p>
 */
final class DeadlineDelivery {

    private final List<SagaModel> sagas;
    private final Collaborators with;
    private final InstanceLocks instanceLocks;

    DeadlineDelivery(List<SagaModel> sagas, Collaborators with, InstanceLocks instanceLocks) {
        this.sagas = List.copyOf(sagas);
        this.with = with;
        this.instanceLocks = instanceLocks;
    }

    HandlingReport deliver(DeadlineReached reached) {
        Optional<SagaModel> saga = sagas.stream().filter(model -> model.typeName().equals(reached.sagaType()))
                .findFirst();
        Optional<Step> step = saga.flatMap(model -> model.expiryStep(reached.deadlineName()));
        if (step.isEmpty()) {
            HandlingReport empty = new HandlingReport(reached, List.of());
            String message = "No registered saga type " + reached.sagaType() + " has a @DeadlineExpired step for"
                    + " deadline '" + reached.deadlineName() + "'; the deadline should be redelivered to a process"
                    + " that runs this saga";
            EventHandlingException unknown = new EventHandlingException(message, empty,
                    List.of(new IllegalStateException(message)));
            with.diagnostics().eventNotHandled(reached, unknown);
            throw unknown;
        }
        SagaHandlingResult result = traced(saga.get(), step.get(), reached);
        return new HandlingReport(reached, List.of(result));
    }

    private SagaHandlingResult traced(SagaModel saga, Step step, DeadlineReached reached) {
        SagaInstrumentation telemetry = with.instrumentation();
        try (SagaInstrumentation.Traced span = telemetry.start(SagaInstrumentation.HANDLE)) {
            span.set(SagaInstrumentation.SAGA_TYPE, saga.typeName())
                    .set(SagaInstrumentation.EVENT_TYPE, DeadlineReached.class.getName())
                    .set(SagaInstrumentation.SAGA_ID, reached.sagaId())
                    .set(SagaInstrumentation.DEADLINE_NAME, reached.deadlineName());
            SagaHandlingResult result;
            try {
                Trigger trigger = new Trigger(saga, step, reached, reached.sagaId(), false);
                result = new AttemptLoop(with).run(trigger, instanceLocks.acquire(reached.sagaId()));
            } catch (RuntimeException | Error failure) {
                span.fail(failure);
                throw failure;
            }
            span.set(SagaInstrumentation.HANDLING_OUTCOME, result.outcome().name());
            if (result.failure() != null) {
                span.failType(result.failure().causeType(), result.failure().message());
            }
            EventRouter.countOutcome(telemetry, span, result);
            if (result.outcome() != HandlingOutcome.NOT_HANDLED) {
                telemetry.count(SagaInstrumentation.DEADLINES_COUNTER, Attributes.builder()
                        .put(SagaInstrumentation.SAGA_TYPE, saga.typeName())
                        .put(SagaInstrumentation.DEADLINE_NAME, reached.deadlineName())
                        .put(SagaInstrumentation.DEADLINE_EVENT,
                                result.outcome() == HandlingOutcome.IGNORED ? "ignored" : "expired").build());
            }
            return result;
        }
    }
}
