package io.github.gkosharovdev.sagaforge.core.operations;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * How many saga instances are in each status, and how many failed ones still need attention (are
 * not {@linkplain SagaResolution resolved}).
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code byStatus} is
 * {@code null} or holds a negative or {@code null} count, or if an unresolved count is negative or
 * larger than its status's count. Statuses missing from {@code byStatus} count 0.
 *
 * @param byStatus the number of instances per status; every status is present in the copy
 * @param compensationFailedUnresolved {@code COMPENSATION_FAILED} instances not resolved
 * @param failedUnresolved {@code FAILED} instances not resolved
 */
public record InstanceCounts(
    Map<SagaStatus, Long> byStatus, long compensationFailedUnresolved, long failedUnresolved) {

  public InstanceCounts {
    Checks.nonNull(byStatus, "byStatus");
    EnumMap<SagaStatus, Long> copy = new EnumMap<>(SagaStatus.class);
    for (SagaStatus status : SagaStatus.values()) {
      copy.put(status, 0L);
    }
    byStatus.forEach(
        (status, count) -> {
          Checks.nonNull(status, "byStatus key");
          Checks.nonNull(count, "byStatus count of " + status);
          if (count < 0) {
            throw new IllegalArgumentException(
                "byStatus count of " + status + " must not be negative, but is " + count);
          }
          copy.put(status, count);
        });
    byStatus = Collections.unmodifiableMap(copy);
    requireWithin(
        compensationFailedUnresolved,
        copy.get(SagaStatus.COMPENSATION_FAILED),
        "compensationFailedUnresolved");
    requireWithin(failedUnresolved, copy.get(SagaStatus.FAILED), "failedUnresolved");
  }

  /**
   * @return no instances at all
   */
  public static InstanceCounts none() {
    return new InstanceCounts(Map.of(), 0, 0);
  }

  /**
   * @return the number of instances in {@code status}
   */
  public long get(SagaStatus status) {
    return byStatus.get(status);
  }

  private static void requireWithin(long value, long total, String name) {
    if (value < 0 || value > total) {
      throw new IllegalArgumentException(
          name + " must be between 0 and " + total + ", but is " + value);
    }
  }
}
