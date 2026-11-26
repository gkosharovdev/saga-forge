package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.util.Optional;
import java.util.Set;

/**
 * Attaches an operator's resolution to an unresolved {@code FAILED} or {@code COMPENSATION_FAILED}
 * instance ({@code specs/015-saga-operations} FR-016): one unit of work that writes the resolution
 * and a {@code RESOLVED} entry, and nothing else.
 *
 * <p>Immutable and thread-safe.
 */
final class MarkResolved implements UnitOfWork.Work<SagaInstance, RepairRejection> {

  private static final Set<SagaStatus> RESOLVABLE =
      Set.of(SagaStatus.FAILED, SagaStatus.COMPENSATION_FAILED);

  private final OperatorRequest request;

  MarkResolved(OperatorRequest request) {
    this.request = request;
  }

  @Override
  public Admission<RepairRejection> admit(Optional<SagaInstance> locked) {
    return RepairAdmissions.admit(locked, RESOLVABLE);
  }

  @Override
  public SagaInstance perform(WorkContext context) {
    return context.lifecycle().resolve(context.sagaId(), request);
  }
}
