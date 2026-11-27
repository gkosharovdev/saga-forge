package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaModel.Injection;

/**
 * What a {@link UnitOfWork.Work} sees of the unit of work that runs it
 * (specs/011-saga-outcome-events contracts/unit-of-work.md): the lifecycle loaded with the locked
 * instance, fresh saga objects with their fields injected, the recorder binding around user code,
 * command identity scoping and failure reports.
 *
 * <p>Not thread-safe: confined to the thread that runs the unit of work.
 */
final class WorkContext {

  private final UnitOfWork unitOfWork;
  private final EngineSettings settings;
  private final CommandGateway gateway;
  private final SagaModel saga;
  private final String sagaId;
  private final String unitOfWorkId;
  private final Correlation correlation;
  private final EngineSagaLifecycle lifecycle;
  private final CommandIds commandIds;

  WorkContext(
      UnitOfWork unitOfWork,
      EngineSettings settings,
      CommandGateway gateway,
      SagaModel saga,
      String sagaId,
      String unitOfWorkId,
      Correlation correlation,
      EngineSagaLifecycle lifecycle,
      CommandIds commandIds) {
    this.unitOfWork = unitOfWork;
    this.settings = settings;
    this.gateway = gateway;
    this.saga = saga;
    this.sagaId = sagaId;
    this.unitOfWorkId = unitOfWorkId;
    this.correlation = correlation;
    this.lifecycle = lifecycle;
    this.commandIds = commandIds;
  }

  /**
   * The lifecycle, loaded with the locked instance in instance scope; {@code null} outside an
   * instance.
   */
  EngineSagaLifecycle lifecycle() {
    return lifecycle;
  }

  /** The instance this unit of work writes; {@code null} outside an instance. */
  String sagaId() {
    return sagaId;
  }

  /** This unit of work's id. */
  String unitOfWorkId() {
    return unitOfWorkId;
  }

  /** This unit of work's metadata (019). */
  SagaMetadata metadata() {
    return unitOfWork.metadata();
  }

  /** This unit of work's correlation. */
  Correlation correlation() {
    return correlation;
  }

  /**
   * The transaction this unit of work runs in, for a work that hands it on (012: a transport's
   * set-aside).
   *
   * @throws IllegalStateException if no transaction is active, which a running unit of work never
   *     allows
   */
  LocalTransaction transaction() {
    return settings
        .transactions()
        .current()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no transaction is active in unit of work " + unitOfWorkId));
  }

  /** The engine's settings. */
  EngineSettings settings() {
    return settings;
  }

  /**
   * A fresh saga object of the instance's type ({@code SagaObjectProvider}, else the no-argument
   * constructor) with its {@code @SagaId}, {@code SagaContext} and {@code CommandGateway} fields
   * injected (004 FR-009).
   */
  Object newSagaObject() {
    Class<?> type = saga.type();
    // start() refused any saga type a registered provider does not support (G-ENG-21).
    Object sagaObject =
        settings
            .sagaObjects()
            .map(provider -> provider.newInstance(type))
            .orElseGet(saga::newInstance);
    if (!type.isInstance(sagaObject)) {
      throw new IllegalStateException(
          "SagaObjectProvider returned " + sagaObject + " for @Saga class " + type.getName());
    }
    EngineSagaContext context =
        new EngineSagaContext(sagaId, settings.contextStore(), settings.sources().clock());
    saga.inject(sagaObject, new Injection(sagaId, context, gateway, unitOfWork.metadata()));
    return sagaObject;
  }

  /** Runs {@code userCode} with this unit of work bound as the thread's domain-event recorder. */
  void recording(Runnable userCode) {
    DomainEventScope.runWith(unitOfWork, userCode);
  }

  /** Scopes the identities of the commands sent next to {@code methodName} (005 FR-001). */
  void running(String methodName) {
    commandIds.startStep(methodName);
  }

  /** {@link #running(String)} for one occurrence of a step that can run more than once (017). */
  void running(String methodName, String occurrence) {
    commandIds.startStep(methodName, occurrence);
  }

  /**
   * Reports a failure; the first report is kept and fails the unit of work, even if the reporter
   * catches it.
   */
  void reportFailure(Throwable failure) {
    unitOfWork.failed(failure);
  }

  /** Throws the first reported failure, if any. */
  void failIfReported() {
    unitOfWork.failIfReported();
  }
}
