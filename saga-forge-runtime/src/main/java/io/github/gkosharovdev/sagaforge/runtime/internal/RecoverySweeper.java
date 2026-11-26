package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.AbandonedStartPolicy;
import io.github.gkosharovdev.sagaforge.runtime.RecoverySettings;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.opentelemetry.api.common.Attributes;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Resumes stalled compensations and handles abandoned starts ({@code specs/015-saga-operations}
 * research R8). One {@link #sweep()} runs two batched queries and repairs each candidate through
 * {@link EngineSagaOperations}, whose admissions re-check the instance under its lock: that, not
 * leadership, is what makes concurrent sweeps on several instances safe. An error on one instance
 * is logged and counted, and the sweep goes on.
 *
 * <p>{@link #start} schedules sweeps at the settings' interval; {@link #stop} is idempotent, stops
 * scheduling and waits for a running sweep. Thread-safe; sweeps never overlap within one process.
 */
final class RecoverySweeper {

  /** The name of the scheduled thread. */
  static final String THREAD_NAME = "saga-forge-recovery";

  private final EngineSagaOperations operations;
  private final RecoverySettings settings;
  private final UnitOfWork.Collaborators with;
  private final AbandonedStartReports reports = new AbandonedStartReports();
  private final ReentrantLock sweeping = new ReentrantLock();
  private final Object scheduling = new Object();
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> scheduled;

  RecoverySweeper(
      EngineSagaOperations operations, RecoverySettings settings, UnitOfWork.Collaborators with) {
    this.operations = operations;
    this.settings = settings;
    this.with = with;
  }

  /** The settings this sweeper runs with. */
  RecoverySettings settings() {
    return settings;
  }

  /**
   * Runs one sweep now, waiting for one already running in this process to finish first.
   *
   * @return what the sweep did
   */
  RecoveryReport sweep() {
    sweeping.lock();
    try {
      Instant now = with.settings().sources().now();
      int[] counts = new int[4];
      resumeStalled(now, counts);
      handleAbandoned(now, counts);
      return new RecoveryReport(counts[0], counts[1], counts[2], counts[3]);
    } finally {
      sweeping.unlock();
    }
  }

  private void resumeStalled(Instant now, int[] counts) {
    List<SagaInstance> stalled =
        candidates(SagaStatus.COMPENSATING, now.minus(settings.stallAge()));
    for (SagaInstance instance : stalled) {
      Correlation about = about(instance);
      try {
        RepairResult result = operations.resumeStalled(instance.sagaId(), settings.stallAge(), now);
        if (result instanceof RepairResult.Applied applied) {
          counts[0]++;
          count("compensation_resumed");
          with.diagnostics().recoveryCompensationResumed(about, applied.instance().status().name());
        }
      } catch (RuntimeException failure) {
        counts[3]++;
        count("error");
        with.diagnostics().recoveryFailed(about, failure);
      }
    }
  }

  private void handleAbandoned(Instant now, int[] counts) {
    Instant abandonedBefore = now.minus(settings.abandonmentAge());
    List<SagaInstance> abandoned =
        candidates(SagaStatus.CREATED, abandonedBefore).stream()
            .filter(instance -> !InstanceChecks.anyStepCompleted(instance))
            .toList();
    if (abandoned.size() < settings.batchSize()) {
      reports.retainOnly(
          abandoned.stream().map(SagaInstance::sagaId).collect(Collectors.toUnmodifiableSet()));
    }
    for (SagaInstance instance : abandoned) {
      Correlation about = about(instance);
      try {
        if (settings.abandonedStartPolicy() == AbandonedStartPolicy.FAIL) {
          if (operations.failAbandonedStart(instance.sagaId(), abandonedBefore)) {
            counts[2]++;
            count("start_failed");
          }
        } else if (reports.shouldReport(instance.sagaId(), now, settings.abandonmentAge())) {
          counts[1]++;
          count("start_reported");
          with.diagnostics().recoveryStartAbandoned(about, instance.createdAt());
        }
      } catch (RuntimeException failure) {
        counts[3]++;
        count("error");
        with.diagnostics().recoveryFailed(about, failure);
      }
    }
  }

  private List<SagaInstance> candidates(SagaStatus status, Instant modifiedBefore) {
    Set<String> types =
        operations.registeredTypes().isEmpty() ? Set.of() : operations.registeredTypes();
    return operations
        .list(
            SagaInstanceQuery.all()
                .withStatuses(status)
                .withSagaTypes(types.toArray(String[]::new))
                .modifiedBefore(modifiedBefore)
                .limit(settings.batchSize()))
        .instances();
  }

  private void count(String kind) {
    with.instrumentation()
        .count(
            SagaInstrumentation.RECOVERY_COUNTER,
            Attributes.of(SagaInstrumentation.RECOVERY_KIND, kind));
  }

  private static Correlation about(SagaInstance instance) {
    return new Correlation(null, instance.sagaType(), instance.sagaId(), null);
  }

  /**
   * Schedules a sweep every {@code settings.interval()} on {@code executorSupplier}'s executor; the
   * first runs one interval from now.
   *
   * @throws IllegalStateException if already started
   */
  void start(Supplier<ScheduledExecutorService> executorSupplier) {
    synchronized (scheduling) {
      if (executor != null) {
        throw new IllegalStateException("the recovery sweeper is already started");
      }
      executor = executorSupplier.get();
      long every = settings.interval().toMillis();
      scheduled =
          executor.scheduleWithFixedDelay(this::sweepQuietly, every, every, TimeUnit.MILLISECONDS);
    }
  }

  /** Stops scheduling and waits for a running sweep to end; does nothing if not started. */
  void stop() {
    ScheduledExecutorService stopping;
    synchronized (scheduling) {
      if (executor == null) {
        return;
      }
      scheduled.cancel(false);
      stopping = executor;
      executor = null;
    }
    stopping.shutdown();
    try {
      if (!stopping.awaitTermination(1, TimeUnit.MINUTES)) {
        stopping.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      stopping.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** {@code true} while sweeps are scheduled. */
  boolean isScheduled() {
    synchronized (scheduling) {
      return executor != null;
    }
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException failure) {
      with.diagnostics().recoveryFailed(new Correlation(null, null, null, null), failure);
    }
  }

  /** A single daemon thread named {@value #THREAD_NAME}. */
  static ScheduledExecutorService defaultExecutor() {
    return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, THREAD_NAME);
          thread.setDaemon(true);
          return thread;
        });
  }
}
