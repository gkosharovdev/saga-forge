package io.github.gkosharovdev.sagaforge.core.operations;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds saga instances, repairs the ones that need a person's decision, and runs recovery
 * (specs/015-saga-operations). The runtime implements it; a host obtains it from the running
 * runtime.
 *
 * <p><b>Queries</b> ({@link #find}, {@link #findByAssociation}, {@link #list}, {@link #count},
 * {@link #history}, {@link #context}) read committed state, take no instance lock and change
 * nothing. A view is the {@link SagaInstance} itself; {@code SagaContext} values are never part of
 * it and are read on their own with {@link #context(String)}.
 *
 * <p><b>Repairs</b> ({@link #resumeCompensation}, {@link #compensate}, {@link #markResolved}) each
 * run in the runtime's unit of work under the instance lock, are admitted against the instance's
 * status as read under that lock, record the {@link OperatorRequest} in the lifecycle history in
 * the same transaction as the change, and return only after any compensations they start have
 * ended. An instance in the wrong status yields {@link RepairResult.NotApplied}; nothing is thrown
 * for it.
 *
 * <p>Thread-safe. Repairs, events, command-failure reports and recovery on the same instance are
 * serialised by the instance lock, also across application instances sharing one database.
 */
public interface SagaOperations {

  /**
   * @return the instance {@code sagaId}; empty if there is none
   */
  Optional<SagaInstance> find(String sagaId);

  /**
   * @return every instance, of any saga type, holding association {@code property} = {@code value}
   */
  List<SagaInstance> findByAssociation(String property, String value);

  /**
   * @return the page of instances {@code query} selects
   */
  SagaInstancePage list(SagaInstanceQuery query);

  /**
   * @param sagaTypes the saga type names to count; empty for every type
   * @return the number of instances per status
   */
  InstanceCounts count(Set<String> sagaTypes);

  /**
   * @return the lifecycle history of {@code sagaId} in occurrence order; empty if there is none
   */
  List<SagaLifecycleEntry> history(String sagaId);

  /**
   * Reads the {@code SagaContext} values of an instance. They are business data and may be
   * sensitive; callers that show them decide who may see them.
   *
   * @return the values as stored; empty if the instance has none or does not exist
   */
  Map<String, Object> context(String sagaId);

  /**
   * Continues the compensation of a {@code COMPENSATION_FAILED} or stalled {@code COMPENSATING}
   * instance: every failed compensation runs again, then every remaining one, most recently
   * completed step first. Compensations that already succeeded never run again.
   *
   * @return applied with the instance as it ended ({@code COMPENSATED} or {@code
   *     COMPENSATION_FAILED}); or not applied
   */
  RepairResult resumeCompensation(String sagaId, OperatorRequest request);

  /**
   * Compensates every committed step of a {@code RUNNING} or {@code FAILED} instance, as a {@code
   * FINAL} failure would, with an operator trigger carrying {@code request}.
   *
   * @return applied with the instance as it ended; or not applied
   */
  RepairResult compensate(String sagaId, OperatorRequest request);

  /**
   * Resolves a {@code FAILED} or {@code COMPENSATION_FAILED} instance with no further effect: no
   * step, compensation or command runs, and the status stays.
   *
   * @return applied with the resolved instance; or not applied
   */
  RepairResult markResolved(String sagaId, OperatorRequest request);

  /**
   * Runs one recovery sweep now: resumes stalled compensations and handles abandoned starts, as
   * configured. A host that schedules recovery itself calls this; the runtime's own recovery thread
   * calls it too.
   *
   * @return what the sweep did
   */
  RecoveryReport recover();
}
