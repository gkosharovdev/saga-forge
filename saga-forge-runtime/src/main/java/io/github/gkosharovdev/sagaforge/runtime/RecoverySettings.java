package io.github.gkosharovdev.sagaforge.runtime;

import java.time.Duration;

/**
 * How the runtime recovers stalled compensations and abandoned starts on its own ({@code
 * specs/015-saga-operations} research R8). When {@code enabled}, {@link SagaRuntime#start()} runs
 * one sweep every {@code interval} on the daemon thread {@code saga-forge-recovery}; {@link
 * SagaRuntime#stop()} ends it. A sweep resumes up to {@code batchSize} instances {@code
 * COMPENSATING} and not modified for {@code stallAge}, and handles up to {@code batchSize}
 * instances {@code CREATED} for {@code abandonmentAge} with no step completed according to {@code
 * abandonedStartPolicy}. With {@code enabled = false} nothing is scheduled, and {@code
 * SagaOperations.recover()} still runs one sweep with these ages.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a duration is {@code
 * null}, zero or negative, if {@code batchSize} is outside 1 to 1,000, or if {@code
 * abandonedStartPolicy} is {@code null}.
 *
 * @param enabled whether the runtime schedules sweeps; {@code false} by default
 * @param interval the time between sweeps; 1 minute by default
 * @param stallAge how long a {@code COMPENSATING} instance stays unmodified before a sweep resumes
 *     it; 5 minutes by default
 * @param batchSize how many instances of each kind one sweep handles; 100 by default
 * @param abandonmentAge how long a {@code CREATED} instance waits before a sweep handles it; 15
 *     minutes by default
 * @param abandonedStartPolicy what a sweep does with an abandoned start; {@link
 *     AbandonedStartPolicy#REPORT} by default
 */
public record RecoverySettings(
    boolean enabled,
    Duration interval,
    Duration stallAge,
    int batchSize,
    Duration abandonmentAge,
    AbandonedStartPolicy abandonedStartPolicy) {

  /** The largest {@code batchSize}. */
  public static final int MAX_BATCH_SIZE = 1_000;

  private static final RecoverySettings DEFAULTS =
      new RecoverySettings(
          false,
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          100,
          Duration.ofMinutes(15),
          AbandonedStartPolicy.REPORT);

  public RecoverySettings {
    requirePositive(interval, "interval");
    requirePositive(stallAge, "stallAge");
    requirePositive(abandonmentAge, "abandonmentAge");
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize must be between 1 and " + MAX_BATCH_SIZE + ", but is " + batchSize);
    }
    if (abandonedStartPolicy == null) {
      throw new IllegalArgumentException("abandonedStartPolicy must not be null");
    }
  }

  /**
   * @return the defaults, with scheduling off
   */
  public static RecoverySettings defaults() {
    return DEFAULTS;
  }

  /**
   * @return the defaults, with scheduling on
   */
  public static RecoverySettings enabledWithDefaults() {
    return DEFAULTS.withEnabled(true);
  }

  /**
   * @param on whether the runtime schedules sweeps
   * @return these settings with {@code enabled} replaced
   */
  public RecoverySettings withEnabled(boolean on) {
    return new RecoverySettings(
        on, interval, stallAge, batchSize, abandonmentAge, abandonedStartPolicy);
  }

  /**
   * @param every the time between sweeps
   * @return these settings with {@code interval} replaced
   */
  public RecoverySettings withInterval(Duration every) {
    return new RecoverySettings(
        enabled, every, stallAge, batchSize, abandonmentAge, abandonedStartPolicy);
  }

  /**
   * @param age the stall age
   * @return these settings with {@code stallAge} replaced
   */
  public RecoverySettings withStallAge(Duration age) {
    return new RecoverySettings(
        enabled, interval, age, batchSize, abandonmentAge, abandonedStartPolicy);
  }

  /**
   * @param size the batch size
   * @return these settings with {@code batchSize} replaced
   */
  public RecoverySettings withBatchSize(int size) {
    return new RecoverySettings(
        enabled, interval, stallAge, size, abandonmentAge, abandonedStartPolicy);
  }

  /**
   * @param age the abandonment age
   * @return these settings with {@code abandonmentAge} replaced
   */
  public RecoverySettings withAbandonmentAge(Duration age) {
    return new RecoverySettings(enabled, interval, stallAge, batchSize, age, abandonedStartPolicy);
  }

  /**
   * @param policy what a sweep does with an abandoned start
   * @return these settings with {@code abandonedStartPolicy} replaced
   */
  public RecoverySettings withAbandonedStartPolicy(AbandonedStartPolicy policy) {
    return new RecoverySettings(enabled, interval, stallAge, batchSize, abandonmentAge, policy);
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive, but is " + duration);
    }
  }
}
