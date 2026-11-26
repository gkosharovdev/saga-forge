package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Moves a {@code COMPENSATION_FAILED} or stalled {@code COMPENSATING} instance back to running its
 * compensations ({@code specs/015-saga-operations} research R4): one unit of work that writes
 * {@code COMPENSATING} and a {@code COMPENSATION_RESUMED} entry. The caller then runs {@link
 * CompensationRun}.
 *
 * <p>Immutable and thread-safe.
 */
final class ResumeCompensation implements UnitOfWork.Work<SagaInstance, RepairRejection> {

  private static final Set<SagaStatus> RESUMABLE =
      Set.of(SagaStatus.COMPENSATION_FAILED, SagaStatus.COMPENSATING);

  private final OperatorRequest request;
  private final Instant stalledBefore;

  private ResumeCompensation(OperatorRequest request, Instant stalledBefore) {
    this.request = request;
    this.stalledBefore = stalledBefore;
  }

  /** An operator's resume of an unresolved {@code COMPENSATION_FAILED} or {@code COMPENSATING}. */
  static ResumeCompensation byOperator(OperatorRequest request) {
    return new ResumeCompensation(request, null);
  }

  /**
   * The recovery sweeper's resume of a {@code COMPENSATING} instance not modified since {@code now
   * - stallAge}.
   */
  static ResumeCompensation stalled(Duration stallAge, Instant now) {
    return new ResumeCompensation(
        OperatorRequest.recovery("stalled compensation resumed after " + stallAge),
        now.minus(stallAge));
  }

  @Override
  public Admission<RepairRejection> admit(Optional<SagaInstance> locked) {
    if (stalledBefore == null) {
      return RepairAdmissions.admit(locked, RESUMABLE);
    }
    Admission<RepairRejection> admission =
        RepairAdmissions.admit(locked, Set.of(SagaStatus.COMPENSATING));
    if (admission instanceof Admission.Proceed<RepairRejection>
        && !locked.get().lastModifiedAt().isBefore(stalledBefore)) {
      return Admission.skip(RepairRejection.WRONG_STATUS);
    }
    return admission;
  }

  @Override
  public SagaInstance perform(WorkContext context) {
    return context.lifecycle().resumeCompensation(context.sagaId(), request);
  }
}
