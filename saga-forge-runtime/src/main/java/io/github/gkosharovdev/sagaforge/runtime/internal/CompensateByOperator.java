package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Starts compensation of an unresolved {@code CREATED}, {@code RUNNING} or {@code FAILED} instance
 * because an operator asked ({@code specs/015-saga-operations} research R5). Unlike {@link
 * StartCompensation} it also starts when nothing committed: the instance then ends {@code
 * COMPENSATED} in the same write. The trigger names the most recently completed step, or the start
 * step when none completed, and carries the failure of a {@code FAILED} instance.
 *
 * <p>Immutable and thread-safe.
 */
final class CompensateByOperator implements UnitOfWork.Work<CompensationTrigger, RepairRejection> {

  private static final Set<SagaStatus> COMPENSABLE =
      Set.of(SagaStatus.CREATED, SagaStatus.RUNNING, SagaStatus.FAILED);

  private final SagaModel saga;
  private final OperatorRequest request;

  CompensateByOperator(SagaModel saga, OperatorRequest request) {
    this.saga = saga;
    this.request = request;
  }

  @Override
  public Admission<RepairRejection> admit(Optional<SagaInstance> locked) {
    return RepairAdmissions.admit(locked, COMPENSABLE);
  }

  @Override
  public CompensationTrigger perform(WorkContext context) {
    SagaInstance current = context.lifecycle().current();
    CompensationTrigger trigger =
        CompensationTrigger.byOperator(
            request,
            latestCompletedStep(current).orElse(saga.startStep().name()),
            current.failure(),
            context.settings().sources().now());
    context.lifecycle().startOperatorCompensation(context.sagaId(), trigger);
    return trigger;
  }

  private static Optional<String> latestCompletedStep(SagaInstance instance) {
    return instance.steps().entrySet().stream()
        .filter(entry -> entry.getValue().status() == StepStatus.COMPLETED)
        .max(
            Comparator.comparingInt(
                (Map.Entry<String, StepRecord> entry) -> entry.getValue().completionSequence()))
        .map(Map.Entry::getKey);
  }
}
