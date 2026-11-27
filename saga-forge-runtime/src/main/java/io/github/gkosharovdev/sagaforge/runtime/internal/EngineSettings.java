package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The host plug-ins and defaults one engine runs with, as collected by {@code SagaRuntime.Builder}
 * (data-model.md "Plug-ins").
 *
 * <p><strong>Not API.</strong> Public only so that {@code SagaRuntime} can hand it to {@link
 * EngineRuntime}.
 *
 * <p>Immutable (the lists are copied); thread-safe if the plug-ins are.
 *
 * @param storage instance store; joins the unit of work's transaction
 * @param contextStore {@code SagaContext} store; never joins it
 * @param transactions the host's local transactions
 * @param dispatcher outbound transport
 * @param handlerInstances objects hosting service handlers, loaders and savers; {@link
 *     #noHandlerInstances()} if no registered class needs one
 * @param aggregateResolvers host resolvers, first supporting wins
 * @param parameterResolvers host resolvers of non-command handler parameters, first supporting wins
 * @param sagaObjects the host's saga-object provider, if any
 * @param outbox receives committed units of work's buffered events
 * @param outboxTiming {@code outbox.timing()}, read once when the engine was built
 * @param sources clock and id suppliers
 * @param retry retry limits and the pause between attempts
 * @param commandInterceptor sees every command handling; {@link CommandInterceptor#proceeding()} by
 *     default
 * @param openTelemetry where spans and metrics go; {@link OpenTelemetry#noop()} by default (010)
 * @param telemetry what is recorded through {@code openTelemetry}
 * @param outcomeEvents whether terminal writes hand an outcome event to the outbox; {@code true} by
 *     default (specs/011-saga-outcome-events FR-016)
 * @param properties resolves the keys of {@code @StartDeadline(duration = "${key[:default]}")} at
 *     startup; resolves nothing by default (specs/017-saga-deadlines FR-010)
 * @param domainTypes the vocabularies of domain markers the model is read with; {@link
 *     DomainTypes#builtIn()} by default (specs/016-marker-spi-jmolecules)
 * @param aggregateEventSources take the events aggregates hold after their handlers, first
 *     supporting wins; none by default (specs/016-marker-spi-jmolecules)
 */
public record EngineSettings(
    StorageProvider storage,
    SagaContextStore contextStore,
    TransactionBoundary transactions,
    EventDispatcher dispatcher,
    HandlerInstanceProvider handlerInstances,
    List<AggregateResolver> aggregateResolvers,
    List<HandlerParameterResolver> parameterResolvers,
    Optional<SagaObjectProvider> sagaObjects,
    Outbox outbox,
    Outbox.Timing outboxTiming,
    TimeAndIdentity sources,
    RetrySettings retry,
    CommandInterceptor commandInterceptor,
    OpenTelemetry openTelemetry,
    SagaTelemetrySettings telemetry,
    boolean outcomeEvents,
    Function<String, Optional<String>> properties,
    DomainTypes domainTypes,
    List<AggregateEventSource> aggregateEventSources) {

  /**
   * @throws IllegalArgumentException if a component is {@code null} or a list holds {@code null}
   */
  public EngineSettings {
    requireNonNull(storage, "storage");
    requireNonNull(contextStore, "contextStore");
    requireNonNull(transactions, "transactions");
    requireNonNull(dispatcher, "dispatcher");
    requireNonNull(handlerInstances, "handlerInstances");
    requireNonNull(sagaObjects, "sagaObjects");
    requireNonNull(outbox, "outbox");
    requireNonNull(outboxTiming, "outboxTiming");
    requireNonNull(sources, "sources");
    requireNonNull(retry, "retry");
    requireNonNull(commandInterceptor, "commandInterceptor");
    requireNonNull(openTelemetry, "openTelemetry");
    requireNonNull(telemetry, "telemetry");
    requireNonNull(properties, "properties");
    requireNonNull(domainTypes, "domainTypes");
    aggregateResolvers = copyOf(aggregateResolvers, "aggregateResolvers");
    parameterResolvers = copyOf(parameterResolvers, "parameterResolvers");
    aggregateEventSources = copyOf(aggregateEventSources, "aggregateEventSources");
  }

  /** Settings with Saga Forge's own domain annotations only (016). */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources,
      RetrySettings retry,
      CommandInterceptor commandInterceptor,
      OpenTelemetry openTelemetry,
      SagaTelemetrySettings telemetry,
      boolean outcomeEvents,
      Function<String, Optional<String>> properties) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        retry,
        commandInterceptor,
        openTelemetry,
        telemetry,
        outcomeEvents,
        properties,
        DomainTypes.builtIn(),
        List.of());
  }

  /** Settings that resolve no deadline property (017). */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources,
      RetrySettings retry,
      CommandInterceptor commandInterceptor,
      OpenTelemetry openTelemetry,
      SagaTelemetrySettings telemetry,
      boolean outcomeEvents) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        retry,
        commandInterceptor,
        openTelemetry,
        telemetry,
        outcomeEvents,
        key -> Optional.empty());
  }

  /** Settings with outcome events on (011). */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources,
      RetrySettings retry,
      CommandInterceptor commandInterceptor,
      OpenTelemetry openTelemetry,
      SagaTelemetrySettings telemetry) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        retry,
        commandInterceptor,
        openTelemetry,
        telemetry,
        true);
  }

  /** Settings without telemetry ({@link OpenTelemetry#noop()}). */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources,
      RetrySettings retry,
      CommandInterceptor commandInterceptor) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        retry,
        commandInterceptor,
        OpenTelemetry.noop(),
        SagaTelemetrySettings.defaults());
  }

  /** Settings with the {@linkplain CommandInterceptor#proceeding() proceeding} interceptor. */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources,
      RetrySettings retry) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        retry,
        CommandInterceptor.proceeding());
  }

  /** Settings with {@link RetrySettings#defaults()}. */
  public EngineSettings(
      StorageProvider storage,
      SagaContextStore contextStore,
      TransactionBoundary transactions,
      EventDispatcher dispatcher,
      HandlerInstanceProvider handlerInstances,
      List<AggregateResolver> aggregateResolvers,
      List<HandlerParameterResolver> parameterResolvers,
      Optional<SagaObjectProvider> sagaObjects,
      Outbox outbox,
      Outbox.Timing outboxTiming,
      TimeAndIdentity sources) {
    this(
        storage,
        contextStore,
        transactions,
        dispatcher,
        handlerInstances,
        aggregateResolvers,
        parameterResolvers,
        sagaObjects,
        outbox,
        outboxTiming,
        sources,
        RetrySettings.defaults());
  }

  /**
   * The provider used when no registered class hosts a service handler, loader or saver; it is
   * never asked, and fails with {@link IllegalStateException} if it is.
   */
  public static HandlerInstanceProvider noHandlerInstances() {
    return handlerType -> {
      throw new IllegalStateException(
          "No HandlerInstanceProvider was registered; cannot supply " + handlerType.getName());
    };
  }

  private static <T> List<T> copyOf(List<T> list, String name) {
    requireNonNull(list, name);
    if (list.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(name + " must not contain null elements");
    }
    return List.copyOf(list);
  }

  private static void requireNonNull(Object argument, String argumentName) {
    if (argument == null) {
      throw new IllegalArgumentException(argumentName + " must not be null");
    }
  }
}
