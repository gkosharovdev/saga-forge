package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.runtime.RecoverySettings;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The assembled runtime of one started engine: its saga models, command routes, event router and
 * publisher.
 *
 * <p><strong>Not API.</strong> The composition root behind {@code SagaRuntime}, public only because
 * the engine's public types live in another package; everything it assembles stays package-private.
 * {@code SagaRuntime} assembles it only after {@code validateAll} accepted the registered classes,
 * and owns the started/refused state.
 *
 * <p>Immutable; thread-safe if the host plug-ins are.
 */
public final class EngineRuntime {

  private final List<SagaModel> sagas;
  private final EventRouter router;
  private final CommandFailureHandler commandFailures;
  private final EngineCommandGateway commandGateway;
  private final EventPublisher eventPublisher;
  private final EngineSagaOperations operations;
  private final UnitOfWork.Collaborators with;
  private final Object background = new Object();
  private AutoCloseable instancesGauge;

  private EngineRuntime(
      List<SagaModel> sagas,
      EventRouter router,
      CommandFailureHandler commandFailures,
      EngineCommandGateway commandGateway,
      EventPublisher eventPublisher,
      EngineSagaOperations operations,
      UnitOfWork.Collaborators with) {
    this.operations = operations;
    this.with = with;
    this.sagas = sagas;
    this.router = router;
    this.commandFailures = commandFailures;
    this.commandGateway = commandGateway;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Builds the models and routes of {@code registered}, which {@code validateAll} has accepted.
   *
   * @param registered the registered classes, in registration order
   * @param settings the host plug-ins and defaults
   * @param diagnostics where the runtime logs
   */
  public static EngineRuntime assemble(
      Collection<Class<?>> registered, EngineSettings settings, Diagnostics diagnostics) {
    return assemble(registered, settings, diagnostics, RecoverySettings.defaults());
  }

  /**
   * As {@link #assemble(Collection, EngineSettings, Diagnostics)}, with the recovery settings of
   * {@code specs/015-saga-operations}.
   *
   * @param recovery the ages, batch size, policy and scheduling of recovery sweeps
   */
  public static EngineRuntime assemble(
      Collection<Class<?>> registered,
      EngineSettings settings,
      Diagnostics diagnostics,
      RecoverySettings recovery) {
    List<SagaModel> sagas =
        registered.stream()
            .filter(type -> type.isAnnotationPresent(Saga.class))
            .map(type -> SagaModel.of(type, settings.properties()))
            .toList();
    AggregateAccess access =
        AggregateAccess.of(registered, settings.handlerInstances(), settings.aggregateResolvers());
    EngineCommandGateway gateway =
        EngineCommandGateway.of(registered, access, settings, diagnostics);
    UnitOfWork.Collaborators with =
        new UnitOfWork.Collaborators(
            settings,
            gateway,
            diagnostics,
            gateway.instrumentation(),
            EventSubscriptions.of(registered));
    InstanceLocks instanceLocks = new InstanceLocks();
    return new EngineRuntime(
        sagas,
        new EventRouter(sagas, with, instanceLocks),
        new CommandFailureHandler(sagas, with, instanceLocks),
        gateway,
        new EngineEventPublisher(settings.dispatcher()),
        new EngineSagaOperations(sagas, with, instanceLocks, recovery),
        with);
  }

  /**
   * {@code true} if a registered class hosts a service {@code @CommandHandler} method, an
   * {@code @AggregateLoader} or an {@code @AggregateSaver} instance method, so a {@code
   * HandlerInstanceProvider} must supply its instances.
   */
  public static boolean needsHandlerInstances(Collection<Class<?>> registered) {
    return registered.stream().anyMatch(EngineRuntime::needsHostInstance);
  }

  /**
   * The engine's own startup errors of {@code registered} with {@code settings}' plug-ins
   * (contracts/engine-startup.md "Engine checks and messages"); empty if the model is runnable.
   * Calls only {@code supports} methods.
   *
   * @param registered the registered classes, in registration order
   * @param settings the host plug-ins
   */
  public static List<String> startupErrors(
      Collection<Class<?>> registered, EngineSettings settings) {
    return StartupChecks.errorsOf(registered, settings);
  }

  /** The registered {@code @Saga} classes, in registration order. */
  public List<Class<?>> sagaTypes() {
    return sagas.stream().<Class<?>>map(SagaModel::type).toList();
  }

  /** Handles one inbound domain event (contracts/event-receiver.md). */
  public HandlingReport receive(Object domainEvent) {
    return router.receive(domainEvent, Optional.empty());
  }

  /**
   * Handles one inbound domain event recorded by the unit of work of {@code sourceSagaId} ({@code
   * specs/014-publish-once} contracts/event-receiver-source.md).
   */
  public HandlingReport receive(Object domainEvent, String sourceSagaId) {
    return router.receive(domainEvent, Optional.of(sourceSagaId));
  }

  /** The engine's command gateway, also the one injected into saga objects. */
  public CommandGateway commandGateway() {
    return commandGateway;
  }

  /**
   * Handles one outbox command delivered by a transport ({@code CommandReceiver}, 007 research §3).
   */
  public void receiveCommand(OutboxCommand delivery) {
    commandGateway.receive(delivery);
  }

  /**
   * Reacts to a transport's report of a failed outbox command ({@code
   * CommandReceiver.receiveFailure}, specs/012-outbox-command-failure-feedback
   * contracts/command-receiver-failure.md).
   */
  public void receiveCommandFailure(
      CommandFailure failure, Consumer<LocalTransaction> inTransaction) {
    commandFailures.handle(failure, inTransaction);
  }

  /** The engine's event publisher. */
  public EventPublisher eventPublisher() {
    return eventPublisher;
  }

  /** The engine's operations API ({@code specs/015-saga-operations}). */
  public SagaOperations operations() {
    return operations;
  }

  /**
   * Starts what runs in the background: the {@code saga_forge.instances} gauge, and the recovery
   * sweeper when its settings are enabled.
   */
  public void startBackground() {
    synchronized (background) {
      instancesGauge =
          with.instrumentation()
              .instancesGauge(
                  () -> operations.count(Set.of()),
                  with.settings().telemetry().instanceCountInterval(),
                  with.settings().sources()::now);
      if (operations.sweeper().settings().enabled()) {
        operations.sweeper().start(RecoverySweeper::defaultExecutor);
      }
    }
  }

  /**
   * Stops the background work: the sweeper, after a running sweep ends, and the gauge. Idempotent.
   */
  public void stop() {
    synchronized (background) {
      operations.sweeper().stop();
      if (instancesGauge != null) {
        try {
          instancesGauge.close();
        } catch (Exception ignored) {
          // instancesGauge never throws
        }
        instancesGauge = null;
      }
    }
  }

  private static boolean needsHostInstance(Class<?> type) {
    if (type.isAnnotationPresent(Saga.class)) {
      return false;
    }
    boolean serviceHandlers =
        !type.isAnnotationPresent(Aggregate.class) && hasInstanceMethod(type, CommandHandler.class);
    return serviceHandlers
        || hasInstanceMethod(type, AggregateLoader.class)
        || hasInstanceMethod(type, AggregateSaver.class);
  }

  private static boolean hasInstanceMethod(Class<?> type, Class<? extends Annotation> marker) {
    return Reflection.annotatedMethods(type, marker).stream()
        .anyMatch(method -> !Modifier.isStatic(method.getModifiers()));
  }
}
