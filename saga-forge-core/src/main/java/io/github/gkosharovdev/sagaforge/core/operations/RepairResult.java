package io.github.gkosharovdev.sagaforge.core.operations;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;

/**
 * The outcome of a repair action: {@link Applied}, or {@link NotApplied} with the reason. A repair
 * in the wrong status is an expected outcome, so it is reported, not thrown.
 */
public sealed interface RepairResult {

  /**
   * The action applied.
   *
   * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code instance} is
   * {@code null}.
   *
   * @param instance the instance as it ended after the action; for an action that compensates,
   *     after its compensations ran
   */
  record Applied(SagaInstance instance) implements RepairResult {

    public Applied {
      Checks.nonNull(instance, "instance");
    }
  }

  /**
   * The action did not apply; nothing was written.
   *
   * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code reason} is
   * {@code null}, or if {@code actualStatus} is {@code null} for any reason but {@code NOT_FOUND},
   * or present for {@code NOT_FOUND}.
   *
   * @param reason why
   * @param actualStatus the instance's status; {@code null} for {@code NOT_FOUND}
   */
  record NotApplied(RepairRejection reason, SagaStatus actualStatus) implements RepairResult {

    public NotApplied {
      Checks.nonNull(reason, "reason");
      if ((reason == RepairRejection.NOT_FOUND) != (actualStatus == null)) {
        throw new IllegalArgumentException(
            "actualStatus must be null if and only if reason is NOT_FOUND, but reason is "
                + reason
                + " and actualStatus is "
                + actualStatus);
      }
    }
  }

  /**
   * @return whether the action applied
   */
  default boolean applied() {
    return this instanceof Applied;
  }
}
