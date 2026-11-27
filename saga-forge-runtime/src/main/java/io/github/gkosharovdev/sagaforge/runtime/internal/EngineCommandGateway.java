package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.command.ScheduledCommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInvocation;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The engine's {@link CommandGateway} (002 contracts/command-gateway.md,
 * contracts/contract-deltas.md).
 *
 * <h2>Routes</h2>
 *
 * <p>Built once at {@code start()}: every {@code @CommandHandler} of a registered class becomes the
 * route of its {@code @Command} parameter's exact type.
 *
 * <ul>
 *   <li><em>Service</em> method (host not {@code @Aggregate}): invoked on the host instance from
 *       the {@link HandlerInstanceProvider}.
 *   <li><em>Aggregate instance</em> method: the target is loaded through {@link AggregateAccess} by
 *       the command's {@code @AggregateIdentifier} (absent → {@link AggregateNotFoundException}),
 *       the handler invoked, the aggregate saved.
 *   <li><em>Creation</em>: an {@code @Aggregate} constructor or static factory; the new aggregate
 *       is saved.
 * </ul>
 *
 * <p>The engine supplies the {@code @Command} argument; every other parameter comes from the first
 * {@link HandlerParameterResolver} that {@linkplain HandlerParameterResolver#supports supports} it,
 * chosen at {@code start()} and asked to {@linkplain HandlerParameterResolver#resolve resolve}
 * before each invocation (FR-019). A parameter no resolver supports fails with {@link
 * IllegalStateException} naming handler and parameter; US6 rejects such a model at startup.
 *
 * <p>A {@code CommandId} parameter is supplied by the engine itself, before any host resolver is
 * asked: it is the identity of the command being handled (005 contracts/command-identity.md).
 *
 * <h2>{@link #send(Object)}</h2>
 *
 * <ul>
 *   <li>Inside an engine unit of work bound on this thread: runs the route synchronously in it. Any
 *       failure ({@code null} or non-{@code @Command} command → {@link IllegalArgumentException},
 *       no route → {@link NoCommandHandlerException}, or an exception of the handler, loader,
 *       saver, resolver, provider or parameter resolver) first marks the current {@link
 *       LocalTransaction} rollback-only and reports itself to the unit of work, then leaves {@code
 *       send} unchanged (002 FR-012). Before routing, the unit of work assigns the command its
 *       identity; a command whose identity already committed for the instance is not handled again
 *       ({@code saga.command.skipped}, 005 FR-004), and a handled one is recorded with the running
 *       step.
 *   <li>Outside one (including a thread a handler started): validates the command, then runs the
 *       route in a new standalone unit of work running {@link HandleCommand} (FR-016).
 * </ul>
 *
 * <h2>Interception ({@code specs/009-saga-test-fixture}, Property 36)</h2>
 *
 * <p>Every handling of a command, in a saga or non-saga unit of work or delivered through the
 * {@code CommandReceiver}, goes through the {@link CommandInterceptor} exactly once, with the
 * command's identity bound; the route runs only if the interceptor {@linkplain
 * CommandInvocation#proceed() proceeds}. A skipped command (identity already committed) and an
 * outbox command at the time it is stored are not handed over. The interceptor's exception is
 * handled as the handler's.
 *
 * <p>Immutable; thread-safe if the host plug-ins and client handlers are.
 */
final class EngineCommandGateway implements CommandGateway {

  /** How one command type reaches its handler. */
  @FunctionalInterface
  private interface CommandRoute {

    void handle(Object command);
  }

  /**
   * Supplies a {@code CommandId} handler parameter: the identity of the command being handled.
   * Consulted before the host's resolvers (005 research §2).
   */
  private static final HandlerParameterResolver COMMAND_ID =
      new HandlerParameterResolver() {

        @Override
        public boolean supports(Executable handler, Parameter parameter) {
          return parameter.getType() == CommandId.class;
        }

        @Override
        public Object resolve(Executable handler, Parameter parameter) {
          return CommandIds.bound()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No command is being handled on this thread for the CommandId parameter of "
                              + handler));
        }
      };

  /**
   * Supplies a {@code SagaMetadata} handler parameter: the metadata of the unit of work handling
   * the command ({@code specs/019-process-metadata} contracts/saga-code.md). Consulted before the
   * host's resolvers, never asking them.
   */
  private static final HandlerParameterResolver SAGA_METADATA =
      new HandlerParameterResolver() {

        @Override
        public boolean supports(Executable handler, Parameter parameter) {
          return parameter.getType() == SagaMetadata.class;
        }

        @Override
        public Object resolve(Executable handler, Parameter parameter) {
          return currentMetadata();
        }
      };

  /** The metadata of the unit of work bound to this thread, or empty outside one. */
  private static SagaMetadata currentMetadata() {
    return BoundUnitOfWork.current().map(BoundUnitOfWork::metadata).orElse(SagaMetadata.empty());
  }

  private final Map<Class<?>, CommandRoute> routes;
  private final TransactionBoundary transactions;
  private final boolean outboxStoresCommands;
  private final boolean outboxSchedules;
  private final BiConsumer<SagaMetadata, Runnable> standaloneUnitsOfWork;
  private final MetadataScopes metadataScopes;
  private final Diagnostics diagnostics;
  private final CommandInterceptor interceptor;
  private final SagaInstrumentation instrumentation;
  private final DomainTypes domainTypes;

  private EngineCommandGateway(
      Map<Class<?>, CommandRoute> routes,
      TransactionBoundary transactions,
      boolean outboxStoresCommands,
      boolean outboxSchedules,
      BiConsumer<SagaMetadata, Runnable> standaloneUnitsOfWork,
      MetadataScopes metadataScopes,
      Diagnostics diagnostics,
      CommandInterceptor interceptor,
      SagaInstrumentation instrumentation,
      DomainTypes domainTypes) {
    this.routes = Map.copyOf(routes);
    this.transactions = transactions;
    this.outboxStoresCommands = outboxStoresCommands;
    this.outboxSchedules = outboxSchedules;
    this.standaloneUnitsOfWork = standaloneUnitsOfWork;
    this.metadataScopes = metadataScopes;
    this.diagnostics = diagnostics;
    this.interceptor = interceptor;
    this.instrumentation = instrumentation;
    this.domainTypes = domainTypes;
  }

  /** The runtime's instrumentation, built once per engine from its settings (010). */
  SagaInstrumentation instrumentation() {
    return instrumentation;
  }

  /**
   * Whether {@code parameter} of {@code handler} is the engine-supplied {@code CommandId} (005
   * FR-003); startup checks count it as resolvable.
   */
  static boolean isCommandIdParameter(Executable handler, Parameter parameter) {
    return COMMAND_ID.supports(handler, parameter);
  }

  /**
   * Whether {@code parameter} of {@code handler} is a parameter the engine supplies itself: a
   * {@code CommandId} (005 FR-003) or a {@code SagaMetadata} (019 FR-013); startup checks count it
   * as resolvable.
   */
  static boolean isEngineSuppliedParameter(Executable handler, Parameter parameter) {
    return COMMAND_ID.supports(handler, parameter) || SAGA_METADATA.supports(handler, parameter);
  }

  /**
   * Builds the routing table of {@code registered}; {@code diagnostics} is where non-saga units of
   * work log.
   *
   * @throws IllegalStateException if two handlers take the same command type ({@code validateAll}
   *     rejects that first)
   */
  static EngineCommandGateway of(
      Collection<Class<?>> registered,
      AggregateAccess access,
      EngineSettings settings,
      Diagnostics diagnostics) {
    RouteFactory factory = new RouteFactory(access, settings);
    Map<Class<?>, CommandRoute> routes = new LinkedHashMap<>();
    for (Class<?> host : registered) {
      factory.routesOf(host).forEach((commandType, route) -> addRoute(routes, commandType, route));
    }
    return new EngineCommandGateway(
        routes,
        settings.transactions(),
        settings.outbox().supportsCommands(),
        settings.outbox().supportsScheduling(),
        (metadata, handlerCall) -> HandleCommand.run(settings, diagnostics, metadata, handlerCall),
        new MetadataScopes(settings.metadataAccessor(), diagnostics),
        diagnostics,
        settings.commandInterceptor(),
        new SagaInstrumentation(settings.openTelemetry(), settings.telemetry(), diagnostics),
        settings.domainTypes());
  }

  @Override
  public void send(Object command) {
    Optional<BoundUnitOfWork> bound = BoundUnitOfWork.current();
    if (bound.isPresent()) {
      sendWithin(bound.get(), command);
      return;
    }
    requireCommand(command);
    standaloneUnitsOfWork.accept(metadataScopes.capture(), () -> send(command));
  }

  private void sendWithin(BoundUnitOfWork unitOfWork, Object command) {
    try {
      requireCommand(command);
      CommandRoute route = routes.get(command.getClass());
      boolean logged = isOutbox(command);
      if (logged && route == null) {
        throw new NoCommandHandlerException(command.getClass());
      }
      if (logged && !outboxStoresCommands) {
        throw new IllegalStateException(
            "Command "
                + command.getClass().getName()
                + " is delivered OUTBOX, "
                + "but the engine's Outbox does not support commands; use a transport whose outbox does, "
                + "such as the in-memory transport");
      }
      CommandId id = unitOfWork.nextCommandId(command.getClass());
      if (unitOfWork.alreadyHandled(id)) {
        diagnostics.commandSkipped(id);
        try (SagaInstrumentation.Traced span = commandSpan(command, id, "in_unit_of_work")) {
          span.set(SagaInstrumentation.COMMAND_RESULT, "skipped");
        }
        return;
      }
      if (logged) {
        // 007 research §3: stored with the unit of work, handled after it commits, never if it
        // rolls back.
        unitOfWork.recordOutboxCommand(new OutgoingCommand(id, command));
        unitOfWork.commandHandled(id);
        return;
      }
      intercept(command, id, route, "in_unit_of_work");
      unitOfWork.commandHandled(id);
    } catch (RuntimeException | Error failure) {
      transactions.current().ifPresent(LocalTransaction::setRollbackOnly);
      unitOfWork.failed(failure);
      throw failure;
    }
  }

  /**
   * Stores {@code command} with the current unit of work to be handled at {@code at} (017 FR-007,
   * contracts/outbox-and-gateway.md "CommandGateway"); outside one, in a unit of work of its own.
   * Its {@link CommandId} is assigned now, scoped to the sending step, so a final failure reaches
   * the saga (012).
   */
  @Override
  public ScheduledCommandId schedule(Object command, Instant at) {
    if (at == null) {
      throw new IllegalArgumentException("at must not be null");
    }
    requireScheduling();
    Optional<BoundUnitOfWork> bound = BoundUnitOfWork.current();
    if (bound.isPresent()) {
      return scheduleWithin(bound.get(), command, at);
    }
    requireCommand(command);
    AtomicReference<ScheduledCommandId> scheduled = new AtomicReference<>();
    standaloneUnitsOfWork.accept(
        metadataScopes.capture(), () -> scheduled.set(schedule(command, at)));
    return scheduled.get();
  }

  /**
   * Withdraws a scheduled command: one scheduled in the current unit of work is dropped from its
   * batch, an earlier one is cancelled through {@code Outbox.cancel} in the unit of work's
   * transaction (017).
   */
  @Override
  public boolean cancel(ScheduledCommandId id) {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    requireScheduling();
    Optional<BoundUnitOfWork> bound = BoundUnitOfWork.current();
    if (bound.isPresent()) {
      return bound.get().cancelScheduled(id.value());
    }
    AtomicBoolean cancelled = new AtomicBoolean();
    standaloneUnitsOfWork.accept(metadataScopes.capture(), () -> cancelled.set(cancel(id)));
    return cancelled.get();
  }

  private void requireScheduling() {
    if (!outboxSchedules) {
      throw new UnsupportedOperationException(
          "The engine's Outbox cannot schedule messages; scheduled commands"
              + " need an IN_TRANSACTION outbox over a message store that supports scheduling, such as"
              + " InMemoryTransport with PostgresMessageStore");
    }
  }

  private ScheduledCommandId scheduleWithin(
      BoundUnitOfWork unitOfWork, Object command, Instant at) {
    try {
      requireCommand(command);
      if (!isOutbox(command)) {
        throw new IllegalArgumentException(
            "Command "
                + command.getClass().getName()
                + " is not"
                + " @Command(delivery = OUTBOX); only outbox commands can be scheduled");
      }
      if (!routes.containsKey(command.getClass())) {
        throw new NoCommandHandlerException(command.getClass());
      }
      CommandId id = unitOfWork.nextCommandId(command.getClass());
      String messageId = unitOfWork.nextScheduledMessageId();
      unitOfWork.recordScheduled(
          new ScheduledMessage(messageId, MessageKind.COMMAND, command, id, at));
      return new ScheduledCommandId(messageId);
    } catch (RuntimeException | Error failure) {
      transactions.current().ifPresent(LocalTransaction::setRollbackOnly);
      unitOfWork.failed(failure);
      throw failure;
    }
  }

  /**
   * Handles a outbox command delivered by a transport ({@code CommandReceiver}, 007 research §3): a
   * new non-saga unit of work, the handler with the delivery's {@code CommandId} bound, then {@code
   * inTransaction} before commit.
   *
   * @throws IllegalStateException if a unit of work is running on this thread
   */
  void receive(OutboxCommand delivery) {
    if (delivery == null) {
      throw new IllegalArgumentException("delivery must not be null");
    }
    Object command = delivery.command();
    requireCommand(command);
    if (BoundUnitOfWork.current().isPresent()) {
      throw new IllegalStateException(
          "CommandReceiver.receive was called inside a unit of work; a transport "
              + "delivers outbox commands from its own threads");
    }
    CommandRoute route = routes.get(command.getClass());
    standaloneUnitsOfWork.accept(
        delivery.metadata(),
        () -> {
          intercept(command, delivery.commandId(), route, "outbox");
          LocalTransaction transaction =
              transactions
                  .current()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "no transaction is active in the unit of work handling "
                                  + delivery.commandId()));
          delivery.inTransaction().accept(transaction);
        });
  }

  private boolean isOutbox(Object command) {
    return domainTypes.delivery(command.getClass()) == CommandDelivery.OUTBOX;
  }

  /**
   * Hands one handling to the interceptor with {@code id} bound; {@code route} is {@code null} if
   * none exists.
   */
  private void intercept(Object command, CommandId id, CommandRoute route, String delivery) {
    try (SagaInstrumentation.Traced span = commandSpan(command, id, delivery)) {
      try {
        CommandIds.withBound(
            id,
            () -> {
              interceptor.intercept(new Invocation(command, id, route));
              return null;
            });
        span.set(SagaInstrumentation.COMMAND_RESULT, "handled");
      } catch (RuntimeException | Error failure) {
        span.set(SagaInstrumentation.COMMAND_RESULT, "failed").fail(failure);
        throw failure;
      }
    }
  }

  /** A {@code saga_forge.command} span for one handling (010 research §2). */
  private SagaInstrumentation.Traced commandSpan(Object command, CommandId id, String delivery) {
    return instrumentation
        .start(SagaInstrumentation.COMMAND)
        .set(SagaInstrumentation.COMMAND_TYPE, command.getClass().getName())
        .set(SagaInstrumentation.COMMAND_DELIVERY, delivery)
        .set(SagaInstrumentation.COMMAND_ID, id.value())
        .set(SagaInstrumentation.STEP_NAME, id.sagaScoped() ? id.stepName() : null)
        .set(SagaInstrumentation.SAGA_ID, id.sagaScoped() ? id.sagaId() : null)
        .set(SagaInstrumentation.CONTENT, instrumentation.content(command));
  }

  /** The {@link CommandInvocation} of one handling; {@link #proceed()} runs at most once. */
  private static final class Invocation implements CommandInvocation {

    private final Object command;
    private final CommandId commandId;
    private final CommandRoute route;
    private boolean proceeded;

    private Invocation(Object command, CommandId commandId, CommandRoute route) {
      this.command = command;
      this.commandId = commandId;
      this.route = route;
    }

    @Override
    public Object command() {
      return command;
    }

    @Override
    public CommandId commandId() {
      return commandId;
    }

    @Override
    public SagaMetadata metadata() {
      return currentMetadata();
    }

    @Override
    public boolean hasHandler() {
      return route != null;
    }

    @Override
    public void proceed() {
      if (proceeded) {
        throw new IllegalStateException("proceed() was already called for " + commandId);
      }
      proceeded = true;
      if (route == null) {
        throw new NoCommandHandlerException(command.getClass());
      }
      route.handle(command);
    }
  }

  private void requireCommand(Object command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    if (!domainTypes.isCommand(command.getClass())) {
      throw new IllegalArgumentException(
          domainTypes.notMarkedMessage(command.getClass(), DomainRole.COMMAND));
    }
  }

  private static void addRoute(
      Map<Class<?>, CommandRoute> routes, Class<?> commandType, CommandRoute route) {
    if (routes.putIfAbsent(commandType, route) != null) {
      throw new IllegalStateException(
          "Command " + commandType.getName() + " has more than one @CommandHandler");
    }
  }

  /**
   * Turns the {@code @CommandHandler} members of one host class into routes keyed by command type.
   */
  private static final class RouteFactory {

    private final AggregateAccess access;
    private final HandlerInstanceProvider instances;
    private final List<HandlerParameterResolver> parameterResolvers;
    private final DomainTypes domainTypes;
    private final EventHarvest harvest;

    private RouteFactory(AggregateAccess access, EngineSettings settings) {
      this.access = access;
      this.instances = settings.handlerInstances();
      this.parameterResolvers = settings.parameterResolvers();
      this.domainTypes = settings.domainTypes();
      this.harvest = new EventHarvest(settings.aggregateEventSources(), domainTypes);
    }

    Map<Class<?>, CommandRoute> routesOf(Class<?> host) {
      Map<Class<?>, CommandRoute> routes = new LinkedHashMap<>();
      for (Constructor<?> constructor :
          Reflection.constructorsWhere(host, this::isCommandHandler)) {
        HandlerArguments arguments = argumentsOf(constructor);
        routes.put(
            arguments.commandType(),
            creation(arguments, values -> Reflection.construct(constructor, values)));
      }
      for (Method method : Reflection.methodsWhere(host, this::isCommandHandler)) {
        HandlerArguments arguments = argumentsOf(method);
        routes.put(arguments.commandType(), methodRoute(host, method, arguments));
      }
      return routes;
    }

    private boolean isCommandHandler(Executable member) {
      return domainTypes.commandHandler(member).isPresent();
    }

    private CommandRoute methodRoute(Class<?> host, Method method, HandlerArguments arguments) {
      boolean isStatic = Modifier.isStatic(method.getModifiers());
      if (domainTypes.isAggregate(host)) {
        return isStatic
            ? creation(arguments, values -> Reflection.invoke(method, null, values))
            : aggregateInstance(host, method, arguments);
      }
      return command ->
          Reflection.invoke(
              method, isStatic ? null : instances.instanceOf(host), arguments.forCommand(command));
    }

    private CommandRoute aggregateInstance(
        Class<?> aggregateType, Method method, HandlerArguments arguments) {
      return command -> {
        Object id = domainTypes.identifierValue(command);
        Object aggregate =
            access
                .load(aggregateType, id)
                .orElseThrow(() -> new AggregateNotFoundException(aggregateType, id));
        Reflection.invoke(method, aggregate, arguments.forCommand(command));
        harvest.harvest(aggregate);
        access.save(aggregate);
      };
    }

    private CommandRoute creation(HandlerArguments arguments, Function<Object[], Object> creator) {
      return command -> {
        Object created = creator.apply(arguments.forCommand(command));
        if (created == null) {
          throw new IllegalStateException(
              "Creation @CommandHandler " + arguments.handlerName() + " returned null");
        }
        harvest.harvest(created);
        access.save(created);
      };
    }

    private HandlerArguments argumentsOf(Executable handler) {
      return HandlerArguments.of(handler, parameterResolvers, domainTypes);
    }
  }

  /**
   * The argument list of one handler: the command at its {@code @Command} position, every other
   * position from the resolver chosen for it at {@code start()}.
   */
  private static final class HandlerArguments {

    private final Executable handler;
    private final int commandPosition;
    private final List<Optional<HandlerParameterResolver>> resolvers;

    private HandlerArguments(
        Executable handler,
        int commandPosition,
        List<Optional<HandlerParameterResolver>> resolvers) {
      this.handler = handler;
      this.commandPosition = commandPosition;
      this.resolvers = resolvers;
    }

    static HandlerArguments of(
        Executable handler, List<HandlerParameterResolver> candidates, DomainTypes domainTypes) {
      Parameter[] parameters = handler.getParameters();
      int commandPosition = -1;
      List<Optional<HandlerParameterResolver>> resolvers = new ArrayList<>();
      for (int position = 0; position < parameters.length; position++) {
        Parameter parameter = parameters[position];
        if (commandPosition < 0 && domainTypes.isCommand(parameter.getType())) {
          commandPosition = position;
          resolvers.add(Optional.empty());
        } else if (COMMAND_ID.supports(handler, parameter)) {
          resolvers.add(Optional.of(COMMAND_ID));
        } else if (SAGA_METADATA.supports(handler, parameter)) {
          resolvers.add(Optional.of(SAGA_METADATA));
        } else {
          resolvers.add(
              candidates.stream()
                  .filter(candidate -> candidate.supports(handler, parameter))
                  .findFirst());
        }
      }
      if (commandPosition < 0) {
        throw new IllegalStateException(
            "@CommandHandler " + handler + " declares no @Command parameter");
      }
      return new HandlerArguments(handler, commandPosition, List.copyOf(resolvers));
    }

    Class<?> commandType() {
      return handler.getParameterTypes()[commandPosition];
    }

    String handlerName() {
      String owner = handler.getDeclaringClass().getSimpleName();
      return owner + "." + (handler instanceof Constructor<?> ? owner : handler.getName());
    }

    Object[] forCommand(Object command) {
      Parameter[] parameters = handler.getParameters();
      Object[] values = new Object[parameters.length];
      for (int position = 0; position < parameters.length; position++) {
        values[position] =
            position == commandPosition ? command : resolve(position, parameters[position]);
      }
      return values;
    }

    private Object resolve(int position, Parameter parameter) {
      HandlerParameterResolver resolver =
          resolvers
              .get(position)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "@CommandHandler "
                              + handlerName()
                              + " parameter #"
                              + (position + 1)
                              + " "
                              + parameter.getType().getSimpleName()
                              + " cannot be resolved: no HandlerParameterResolver"
                              + " supports it"));
      return resolver.resolve(handler, parameter);
    }
  }
}
