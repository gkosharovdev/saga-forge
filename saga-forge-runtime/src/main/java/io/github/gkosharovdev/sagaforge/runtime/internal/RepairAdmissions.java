package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.util.Optional;
import java.util.Set;

/**
 * The admission every operator repair shares ({@code specs/015-saga-operations} contracts
 * operations-api.md R2 to R4): a missing instance is {@code NOT_FOUND}, a resolved one {@code
 * ALREADY_RESOLVED}, and one in another status {@code WRONG_STATUS}.
 *
 * <p>Stateless and thread-safe.
 */
final class RepairAdmissions {

  private RepairAdmissions() {}

  /**
   * @param locked the instance the unit of work locked, or empty
   * @param allowed the statuses the repair applies to
   * @return proceed, or the rejection
   */
  static Admission<RepairRejection> admit(Optional<SagaInstance> locked, Set<SagaStatus> allowed) {
    if (locked.isEmpty()) {
      return Admission.skip(RepairRejection.NOT_FOUND);
    }
    if (locked.get().isResolved()) {
      return Admission.skip(RepairRejection.ALREADY_RESOLVED);
    }
    if (!allowed.contains(locked.get().status())) {
      return Admission.skip(RepairRejection.WRONG_STATUS);
    }
    return Admission.proceed();
  }
}
