package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstancePage;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.InstanceLocks.Lease;
import io.opentelemetry.api.common.Attributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The runtime's {@link SagaOperations} ({@code specs/015-saga-operations} data-model.md "Runtime
 * internals"), and the one entry point the recovery sweeper repairs through.
 *
 * <p>Queries read storage directly: they take no instance lock and write nothing (contracts
 * operations-api.md O6). Each repair holds the instance lock the event router and the command
 * failure handler share, runs its work in one {@link UnitOfWork}, runs the remaining compensations
 * with {@link CompensationRun} where the work started or resumed them, and releases the committed
 * outboxes after the lock, as {@link CommandFailureHandler} does.
 *
 * <p>Thread-safe if the host plug-ins are.
 */
final class EngineSagaOperations implements SagaOperations {

  static final String RESUME = "resume-compensation";
  static final String COMPENSATE = "compensate";
  static final String RESOLVE = "resolve";

  private final Map<String, SagaModel> sagasByType;
  private final UnitOfWork.Collaborators with;
  private final InstanceLocks instanceLocks;
  private final RecoverySweeper sweeper;

  /**
   * @param sagas the registered saga types
   * @param with the collaborators every unit of work shares
   * @param instanceLocks the instance locks the event router holds
   * @param recovery the ages, batch size and policy of {@link #recover()}
   */
  EngineSagaOperations(
      List<SagaModel> sagas,
      UnitOfWork.Collaborators with,
      InstanceLocks instanceLocks,
      io.github.gkosharovdev.sagaforge.runtime.RecoverySettings recovery) {
    this.sagasByType =
        sagas.stream()
            .collect(Collectors.toUnmodifiableMap(SagaModel::typeName, Function.identity()));
    this.with = with;
    this.instanceLocks = instanceLocks;
    this.sweeper = new RecoverySweeper(this, recovery, with);
  }

  /** The sweeper that runs {@link #recover()}, which the runtime also schedules. */
  RecoverySweeper sweeper() {
    return sweeper;
  }

  // --- queries ---------------------------------------------------------------------------------

  @Override
  public Optional<SagaInstance> find(String sagaId) {
    return storage().findById(requireText(sagaId, "sagaId"));
  }

  @Override
  public List<SagaInstance> findByAssociation(String property, String value) {
    return storage()
        .findByAssociation(requireText(property, "property"), requireNonNull(value, "value"));
  }

  @Override
  public SagaInstancePage list(SagaInstanceQuery query) {
    return storage().find(requireNonNull(query, "query"));
  }

  @Override
  public InstanceCounts count(Set<String> sagaTypes) {
    return storage().countByStatus(requireNonNull(sagaTypes, "sagaTypes"));
  }

  @Override
  public List<SagaLifecycleEntry> history(String sagaId) {
    return storage().history(requireText(sagaId, "sagaId"));
  }

  @Override
  public Map<String, Object> context(String sagaId) {
    return with.settings()
        .contextStore()
        .find(requireText(sagaId, "sagaId"))
        .map(SagaContextRecord::values)
        .<Map<String, Object>>map(Map::copyOf)
        .orElse(Map.of());
  }

  // --- repairs ---------------------------------------------------------------------------------

  @Override
  public RepairResult resumeCompensation(String sagaId, OperatorRequest request) {
    requireNonNull(request, "request");
    return repair(
        RESUME,
        requireText(sagaId, "sagaId"),
        request,
        saga -> ResumeCompensation.byOperator(request));
  }

  @Override
  public RepairResult compensate(String sagaId, OperatorRequest request) {
    requireNonNull(request, "request");
    return repair(
        COMPENSATE,
        requireText(sagaId, "sagaId"),
        request,
        saga -> new CompensateByOperator(saga, request));
  }

  @Override
  public RepairResult markResolved(String sagaId, OperatorRequest request) {
    requireNonNull(request, "request");
    return repair(
        RESOLVE, requireText(sagaId, "sagaId"), request, saga -> new MarkResolved(request));
  }

  @Override
  public RecoveryReport recover() {
    return sweeper.sweep();
  }

  /**
   * The sweeper's resume of a stalled compensation: as {@link #resumeCompensation}, admitted only
   * while the instance is {@code COMPENSATING} and older than {@code stallAge}.
   */
  RepairResult resumeStalled(String sagaId, Duration stallAge, Instant now) {
    return repair(
        RESUME,
        sagaId,
        OperatorRequest.recovery("stalled compensation resumed after " + stallAge),
        saga -> ResumeCompensation.stalled(stallAge, now));
  }

  /**
   * The sweeper's {@code FAIL} policy: ends {@code sagaId} {@code FAILED} if it is still an
   * abandoned start older than {@code abandonedBefore}.
   *
   * @return whether the instance was failed
   */
  boolean failAbandonedStart(String sagaId, Instant abandonedBefore) {
    ReentrancyGuard.requireNotRunning();
    Optional<SagaModel> saga = modelOf(sagaId);
    if (saga.isEmpty()) {
      return false;
    }
    Correlation about = new Correlation(null, saga.get().typeName(), sagaId, null);
    List<Supplier<List<Throwable>>> afterRelease = new ArrayList<>();
    try {
      try (Lease lease = instanceLocks.acquire(sagaId)) {
        UnitOfWork unitOfWork = UnitOfWork.forInstance(with, saga.get(), sagaId, about);
        switch (unitOfWork.run(new FailAbandonedStart(saga.get(), abandonedBefore))) {
          case UnitOfWork.Skipped<SagaInstance, String> skipped -> {
            return false;
          }
          case UnitOfWork.Committed<SagaInstance, String> committed -> {
            afterRelease.add(committed::release);
            with.diagnostics().recoveryStartFailed(unitOfWork.correlation());
            return true;
          }
          case UnitOfWork.Failed<SagaInstance, String> failed ->
              throw UnitOfWork.propagate(failed.cause());
          case UnitOfWork.Conflicted<SagaInstance, String> conflicted ->
              throw conflicted.conflict();
        }
      }
    } finally {
      afterRelease.forEach(Supplier::get);
    }
  }

  /** The names of the registered saga types. */
  Set<String> registeredTypes() {
    return sagasByType.keySet();
  }

  /** The registered model of {@code sagaId}'s saga type, if the instance exists. */
  Optional<SagaModel> modelOf(String sagaId) {
    return storage().findById(sagaId).map(SagaInstance::sagaType).map(sagasByType::get);
  }

  private <T> RepairResult repair(
      String action,
      String sagaId,
      OperatorRequest request,
      Function<SagaModel, UnitOfWork.Work<T, RepairRejection>> toWork) {
    ReentrancyGuard.requireNotRunning();
    SagaInstrumentation telemetry = with.instrumentation();
    try (SagaInstrumentation.Traced span = telemetry.start(SagaInstrumentation.OPERATION)) {
      span.set(SagaInstrumentation.OPERATION_ACTION, action)
          .set(SagaInstrumentation.SAGA_ID, sagaId)
          .set(SagaInstrumentation.OPERATOR, request.operator());
      try {
        RepairResult result = repairLocked(action, sagaId, request, toWork);
        String outcome =
            result instanceof RepairResult.NotApplied notApplied
                ? notApplied.reason().name()
                : "APPLIED";
        span.set(SagaInstrumentation.OPERATION_RESULT, outcome);
        telemetry.count(
            SagaInstrumentation.OPERATIONS_COUNTER,
            Attributes.of(
                SagaInstrumentation.OPERATION_ACTION,
                action,
                SagaInstrumentation.OPERATION_RESULT,
                outcome));
        return result;
      } catch (RuntimeException | Error failure) {
        span.fail(failure);
        throw failure;
      }
    }
  }

  private <T> RepairResult repairLocked(
      String action,
      String sagaId,
      OperatorRequest request,
      Function<SagaModel, UnitOfWork.Work<T, RepairRejection>> toWork) {
    Optional<SagaModel> found = modelOf(sagaId);
    if (found.isEmpty()) {
      return notApplied(sagaId, action, request, RepairRejection.NOT_FOUND);
    }
    SagaModel saga = found.get();
    Correlation about = new Correlation(null, saga.typeName(), sagaId, null);
    List<Supplier<List<Throwable>>> afterRelease = new ArrayList<>();
    try {
      try (Lease lease = instanceLocks.acquire(sagaId)) {
        UnitOfWork unitOfWork = UnitOfWork.forInstance(with, saga, sagaId, about);
        switch (unitOfWork.run(toWork.apply(saga))) {
          case UnitOfWork.Skipped<T, RepairRejection> skipped -> {
            return notApplied(sagaId, action, request, skipped.reason());
          }
          case UnitOfWork.Committed<T, RepairRejection> committed -> {
            afterRelease.add(committed::release);
            SagaInstance written = storage().findById(sagaId).orElseThrow();
            if (written.status() == SagaStatus.COMPENSATING) {
              new CompensationRun(with)
                  .run(saga, sagaId, about, afterRelease, committed.unitOfWorkId());
            }
            SagaInstance ended = storage().findById(sagaId).orElseThrow();
            with.diagnostics()
                .operationApplied(
                    unitOfWork.correlation(), action, request.operator(), ended.status().name());
            return new RepairResult.Applied(ended);
          }
          case UnitOfWork.Failed<T, RepairRejection> failed ->
              throw UnitOfWork.propagate(failed.cause());
          case UnitOfWork.Conflicted<T, RepairRejection> conflicted -> throw conflicted.conflict();
        }
      }
    } finally {
      afterRelease.forEach(Supplier::get);
    }
  }

  private RepairResult notApplied(
      String sagaId, String action, OperatorRequest request, RepairRejection reason) {
    with.diagnostics().operationNotApplied(sagaId, action, request.operator(), reason.name());
    SagaStatus actual =
        reason == RepairRejection.NOT_FOUND
            ? null
            : storage().findById(sagaId).map(SagaInstance::status).orElse(null);
    if (actual == null) {
      return new RepairResult.NotApplied(RepairRejection.NOT_FOUND, null);
    }
    return new RepairResult.NotApplied(reason, actual);
  }

  private StorageProvider storage() {
    return with.settings().storage();
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
