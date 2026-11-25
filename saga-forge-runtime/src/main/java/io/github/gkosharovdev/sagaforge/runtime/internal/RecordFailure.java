package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Marks an instance {@code FAILED} after a rolled-back unit of work, in a unit of work of its own
 * (004 data-model.md row 9, research.md §7, FR-011, FR-024).
 *
 * <p>{@code fail(sagaId, failure)} is applied to the instance <em>as now stored</em>, which always
 * exists: this write updates the committed creation record and never creates one. It logs {@code
 * saga.instance.failed}. If the stored instance is missing or already terminal, nothing is written,
 * {@code saga.instance.failedNotRecorded} is logged, and the result is still {@code FAILED} with
 * the failure, the instance unchanged.
 *
 * <p>Since 011 the write carries the {@code SagaFailed} outcome event. Its {@code AFTER_COMMIT}
 * outbox runs only after the caller released the instance lock ({@link Written#report}), and its
 * failures are reported in {@code SagaHandlingResult.outboxFailures()}, as for any committed unit
 * of work.
 *
 * <p>A failure of this write itself (store unavailable, lock wait aborted, write rejected) rolls
 * back, is logged as {@code saga.instance.failedNotRecorded} and propagates: no outcome was
 * recorded for the instance, and the event router reports it through {@code EventHandlingException}
 * (004 FR-005).
 *
 * <p><strong>G-ENG-10.</strong> Between the rolled-back unit of work and this write's lock another
 * process may run a unit of work on the same instance. This write then marks an instance that has
 * moved on, or finds it terminal and records nothing. The in-process instance lock closes the
 * window within one engine only.
 */
final class RecordFailure implements UnitOfWork.Work<Void, String> {

  /**
   * The {@code FAILED} result to report, and the outbox step of the write that recorded it (none if
   * nothing was written).
   */
  record Written(SagaHandlingResult result, Supplier<List<Throwable>> afterRelease) {

    /**
     * Releases {@code instanceLease}, runs the outbox step and returns the result with its
     * failures.
     */
    SagaHandlingResult report(Lease instanceLease) {
      instanceLease.close();
      List<Throwable> outboxFailures = afterRelease.get();
      if (outboxFailures.isEmpty()) {
        return result;
      }
      List<Throwable> all = new ArrayList<>(result.outboxFailures());
      all.addAll(outboxFailures);
      return new SagaHandlingResult(
          result.sagaType(),
          result.sagaId(),
          result.outcome(),
          result.started(),
          result.outcomeStep(),
          result.failure(),
          result.unitOfWorkId(),
          all);
    }
  }

  private final SagaFailure failure;

  private RecordFailure(SagaFailure failure) {
    this.failure = failure;
  }

  /**
   * Marks the instance {@code FAILED} with {@code failure}.
   *
   * @param about the correlation of the rolled-back unit of work (saga type, instance and unit of
   *     work set)
   * @param failure what failed
   * @param cause the exception that rolled the unit of work back; added as suppressed to a failure
   *     of this write; {@code null} when a redelivery finds the retries exhausted without running
   *     (005)
   * @param started whether this event's creation write created the instance
   * @return a {@code FAILED} result carrying {@code failure} and the rolled-back unit of work's id,
   *     with the outbox step to run after the instance lock is released
   * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
   */
  static Written record(
      Collaborators with,
      SagaModel saga,
      Correlation about,
      SagaFailure failure,
      Throwable cause,
      boolean started) {
    Diagnostics diagnostics = with.diagnostics();
    Supplier<List<Throwable>> afterRelease = List::of;
    try {
      switch (UnitOfWork.forInstance(with, saga, about.sagaId(), about)
          .run(new RecordFailure(failure))) {
        case UnitOfWork.Skipped<Void, String> skipped ->
            diagnostics.instanceFailedNotRecorded(
                about,
                cause != null
                    ? cause
                    : new IllegalStateException(failure.causeType() + ": " + failure.message()));
        case UnitOfWork.Committed<Void, String> committed -> {
          diagnostics.instanceFailed(about, failure);
          afterRelease = committed::release;
        }
        case UnitOfWork.Failed<Void, String> failed -> throw UnitOfWork.propagate(failed.cause());
        case UnitOfWork.Conflicted<Void, String> conflicted -> throw conflicted.conflict();
      }
    } catch (RuntimeException | Error writeFailure) {
      if (cause != null && writeFailure != cause) {
        writeFailure.addSuppressed(cause);
      }
      diagnostics.instanceFailedNotRecorded(about, writeFailure);
      throw writeFailure;
    }
    return new Written(
        new SagaHandlingResult(
            saga.typeName(),
            about.sagaId(),
            HandlingOutcome.FAILED,
            started,
            null,
            failure,
            about.unitOfWorkId(),
            List.of()),
        afterRelease);
  }

  @Override
  public Admission<String> admit(Optional<SagaInstance> locked) {
    return InstanceChecks.missingOrTerminal(locked)
        ? Admission.skip("missing or terminal")
        : Admission.proceed();
  }

  @Override
  public Void perform(WorkContext context) {
    context.lifecycle().fail(context.sagaId(), failure);
    return null;
  }
}
