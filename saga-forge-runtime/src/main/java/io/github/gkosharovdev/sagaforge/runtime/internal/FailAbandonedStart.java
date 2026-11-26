package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.operations.SagaStartAbandonedException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * Ends an abandoned start {@code FAILED} ({@code specs/015-saga-operations} research R8, policy
 * {@code FAIL}): an instance still {@code CREATED}, with no step completed, last modified before
 * {@code abandonedBefore}. The failure names the start step, the cause type {@link
 * SagaStartAbandonedException}, {@code UNCLASSIFIED}, attempts 1; the write emits {@code
 * SagaFailed}.
 *
 * <p>Immutable and thread-safe.
 */
final class FailAbandonedStart implements UnitOfWork.Work<SagaInstance, String> {

  /** The skip reason when the instance moved on or is not old enough. */
  static final String NOT_ABANDONED = "not-abandoned";

  private final SagaModel saga;
  private final Instant abandonedBefore;

  FailAbandonedStart(SagaModel saga, Instant abandonedBefore) {
    this.saga = saga;
    this.abandonedBefore = abandonedBefore;
  }

  @Override
  public Admission<String> admit(Optional<SagaInstance> locked) {
    boolean abandoned =
        locked
            .filter(instance -> instance.status() == SagaStatus.CREATED)
            .filter(instance -> !InstanceChecks.anyStepCompleted(instance))
            .filter(instance -> instance.lastModifiedAt().isBefore(abandonedBefore))
            .isPresent();
    return abandoned ? Admission.proceed() : Admission.skip(NOT_ABANDONED);
  }

  @Override
  public SagaInstance perform(WorkContext context) {
    SagaInstance current = context.lifecycle().current();
    SagaFailure failure =
        new SagaFailure(
            saga.startStep().name(),
            SagaStartAbandonedException.class.getName(),
            "start abandoned: still CREATED since " + current.lastModifiedAt(),
            context.settings().sources().now(),
            FailureClassification.UNCLASSIFIED,
            1);
    return context.lifecycle().fail(context.sagaId(), failure);
  }
}
