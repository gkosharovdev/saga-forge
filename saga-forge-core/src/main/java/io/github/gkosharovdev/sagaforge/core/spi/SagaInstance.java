package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a running saga instance's persisted state.
 *
 * <p>{@code steps}, {@code outcome} and {@code status} commit together with the step's unit of
 * work. {@code failure} and the {@link SagaStatus#FAILED} status are written after a rollback, in
 * their own write. {@code SagaContext} values are not held here; they live in a {@link
 * SagaContextRecord}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code steps} is {@code
 * null} or contains a {@code null} key or value, if {@code outcome} is present without {@code
 * COMPLETED} (or absent with it), if {@code failure} is present without {@code FAILED} (or absent
 * with it), or if {@code pendingRetry} is present on an instance that is not {@code CREATED} or
 * {@code RUNNING}, if {@code compensationTrigger} breaks its rule, if a step has a compensation
 * state outside compensation, if a {@code COMPENSATED} instance has a step not yet compensated or
 * failed, or if a {@code COMPENSATION_FAILED} instance does not have exactly one failed
 * compensation.
 *
 * @param sagaId unique identifier
 * @param sagaType fully qualified class name of the @Saga class
 * @param state reserved; not populated by the engine. Values that must survive between steps belong
 *     in {@code SagaContext} (a {@link SagaContextRecord}); an unmodifiable defensive copy
 * @param associations association property -> value mappings; an unmodifiable defensive copy
 * @param status current lifecycle status
 * @param createdAt creation timestamp
 * @param lastModifiedAt last state transition timestamp
 * @param steps one entry per {@code @SagaStep} method name; an unmodifiable defensive copy with no
 *     {@code null} keys or values
 * @param outcome name of the reached {@code @EndSaga} step; non-null if and only if {@code status
 *     == COMPLETED}
 * @param failure the recorded failure; non-null if and only if {@code status == FAILED}
 * @param pendingRetry the attempt the engine claimed for a retry of the current unit of work;
 *     nullable, and {@code null} unless {@code status} is {@code CREATED} or {@code RUNNING}
 *     ({@code specs/005-step-retry} FR-015). Written in its own transaction, like {@code failure}
 * @param compensationTrigger why the instance entered compensation; non-null if and only if {@code
 *     status} is {@code COMPENSATING}, {@code COMPENSATED} or {@code COMPENSATION_FAILED} ({@code
 *     specs/006-saga-compensation} FR-017). Written once, by the write that enters {@code
 *     COMPENSATING}
 * @param openDeadlines the deadlines started and neither expired nor cancelled, by name; an
 *     unmodifiable defensive copy; empty unless {@code status} is {@code CREATED} or {@code
 *     RUNNING} ({@code specs/017-saga-deadlines} FR-014). Committed with the step's unit of work
 */
public record SagaInstance(
    String sagaId,
    String sagaType,
    Map<String, Object> state,
    Map<String, String> associations,
    SagaStatus status,
    Instant createdAt,
    Instant lastModifiedAt,
    Map<String, StepRecord> steps,
    String outcome,
    SagaFailure failure,
    PendingRetry pendingRetry,
    CompensationTrigger compensationTrigger,
    Map<String, OpenDeadline> openDeadlines) {
  public SagaInstance {
    openDeadlines = copyOpenDeadlines(openDeadlines);
    state = Map.copyOf(state);
    associations = Map.copyOf(associations);
    steps = copySteps(steps);
    boolean completed = status == SagaStatus.COMPLETED;
    if (completed != (outcome != null)) {
      throw new IllegalArgumentException(
          "outcome must be non-null if and only if status is COMPLETED, but status is "
              + status
              + " and outcome is "
              + outcome);
    }
    boolean failed = status == SagaStatus.FAILED;
    if (failed != (failure != null)) {
      throw new IllegalArgumentException(
          "failure must be non-null if and only if status is FAILED, but status is "
              + status
              + " and failure is "
              + failure);
    }
    boolean active = status == SagaStatus.CREATED || status == SagaStatus.RUNNING;
    if (pendingRetry != null && !active) {
      throw new IllegalArgumentException(
          "pendingRetry must be null unless status is CREATED or RUNNING, but status is " + status);
    }
    boolean compensation = isCompensation(status);
    if (compensation != (compensationTrigger != null)) {
      throw new IllegalArgumentException(
          "compensationTrigger must be non-null if and only if status is "
              + "COMPENSATING, COMPENSATED or COMPENSATION_FAILED, but status is "
              + status);
    }
    requireCompensationStates(status, steps);
    if (!openDeadlines.isEmpty() && !active) {
      throw new IllegalArgumentException(
          "openDeadlines must be empty unless status is CREATED or RUNNING, but status is "
              + status);
    }
  }

  /**
   * An instance without open deadlines, as before {@code specs/017-saga-deadlines}.
   *
   * @throws IllegalArgumentException as for the canonical constructor
   */
  public SagaInstance(
      String sagaId,
      String sagaType,
      Map<String, Object> state,
      Map<String, String> associations,
      SagaStatus status,
      Instant createdAt,
      Instant lastModifiedAt,
      Map<String, StepRecord> steps,
      String outcome,
      SagaFailure failure,
      PendingRetry pendingRetry,
      CompensationTrigger compensationTrigger) {
    this(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        pendingRetry,
        compensationTrigger,
        Map.of());
  }

  /**
   * An instance without compensation.
   *
   * @throws IllegalArgumentException as for the canonical constructor
   */
  public SagaInstance(
      String sagaId,
      String sagaType,
      Map<String, Object> state,
      Map<String, String> associations,
      SagaStatus status,
      Instant createdAt,
      Instant lastModifiedAt,
      Map<String, StepRecord> steps,
      String outcome,
      SagaFailure failure,
      PendingRetry pendingRetry) {
    this(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        pendingRetry,
        null,
        Map.of());
  }

  /**
   * An instance without a pending retry.
   *
   * @throws IllegalArgumentException as for the canonical constructor
   */
  public SagaInstance(
      String sagaId,
      String sagaType,
      Map<String, Object> state,
      Map<String, String> associations,
      SagaStatus status,
      Instant createdAt,
      Instant lastModifiedAt,
      Map<String, StepRecord> steps,
      String outcome,
      SagaFailure failure) {
    this(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        null,
        null,
        Map.of());
  }

  /**
   * @param retry the pending retry to record; nullable
   * @return this instance with {@code pendingRetry} replaced
   * @throws IllegalArgumentException if {@code retry} is non-null and the status is not {@code
   *     CREATED} or {@code RUNNING}
   */
  public SagaInstance withPendingRetry(PendingRetry retry) {
    return new SagaInstance(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        retry,
        compensationTrigger,
        openDeadlines);
  }

  /**
   * @param trigger the compensation trigger to record; nullable
   * @return this instance with {@code compensationTrigger} replaced
   * @throws IllegalArgumentException as for the canonical constructor
   */
  public SagaInstance withCompensationTrigger(CompensationTrigger trigger) {
    return new SagaInstance(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        pendingRetry,
        trigger,
        openDeadlines);
  }

  /**
   * @param deadlines the open deadlines, by name; non-null
   * @return this instance with {@code openDeadlines} replaced
   * @throws IllegalArgumentException as for the canonical constructor
   */
  public SagaInstance withOpenDeadlines(Map<String, OpenDeadline> deadlines) {
    return new SagaInstance(
        sagaId,
        sagaType,
        state,
        associations,
        status,
        createdAt,
        lastModifiedAt,
        steps,
        outcome,
        failure,
        pendingRetry,
        compensationTrigger,
        deadlines);
  }

  /** {@code true} for {@code COMPENSATING}, {@code COMPENSATED} and {@code COMPENSATION_FAILED}. */
  public static boolean isCompensation(SagaStatus status) {
    return status == SagaStatus.COMPENSATING
        || status == SagaStatus.COMPENSATED
        || status == SagaStatus.COMPENSATION_FAILED;
  }

  private static void requireCompensationStates(SagaStatus status, Map<String, StepRecord> steps) {
    int failed = 0;
    for (Map.Entry<String, StepRecord> entry : steps.entrySet()) {
      StepCompensation compensation = entry.getValue().compensation();
      if (compensation == null) {
        continue;
      }
      if (!isCompensation(status)) {
        throw new IllegalArgumentException(
            "step "
                + entry.getKey()
                + " must have no compensation state "
                + "unless status is COMPENSATING, COMPENSATED or COMPENSATION_FAILED, but status is "
                + status);
      }
      CompensationStatus state = compensation.status();
      if (status == SagaStatus.COMPENSATED
          && (state == CompensationStatus.NOT_ATTEMPTED || state == CompensationStatus.FAILED)) {
        throw new IllegalArgumentException(
            "step " + entry.getKey() + " of a COMPENSATED instance must not be " + state);
      }
      if (state == CompensationStatus.FAILED) {
        failed++;
      }
    }
    boolean compensationFailed = status == SagaStatus.COMPENSATION_FAILED;
    if (compensationFailed ? failed != 1 : failed != 0) {
      throw new IllegalArgumentException(
          "exactly one step must have a FAILED compensation if and only if "
              + "status is COMPENSATION_FAILED, but status is "
              + status
              + " with "
              + failed);
    }
  }

  private static Map<String, OpenDeadline> copyOpenDeadlines(Map<String, OpenDeadline> deadlines) {
    if (deadlines == null) {
      throw new IllegalArgumentException("openDeadlines must not be null");
    }
    boolean malformed =
        deadlines.entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null);
    if (malformed) {
      throw new IllegalArgumentException(
          "openDeadlines must not contain blank keys or null values");
    }
    return Map.copyOf(deadlines);
  }

  private static Map<String, StepRecord> copySteps(Map<String, StepRecord> steps) {
    if (steps == null) {
      throw new IllegalArgumentException("steps must not be null");
    }
    for (Map.Entry<String, StepRecord> entry : steps.entrySet()) {
      if (entry.getKey() == null) {
        throw new IllegalArgumentException("steps must not contain null keys");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "steps must not contain null values (step " + entry.getKey() + ")");
      }
    }
    return Map.copyOf(steps);
  }
}
