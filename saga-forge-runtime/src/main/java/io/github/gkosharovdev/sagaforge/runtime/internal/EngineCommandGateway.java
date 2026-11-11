package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * The engine's {@link CommandGateway} (002 contracts/command-gateway.md, contracts/contract-deltas.md).
 *
 * <h2>Routes</h2>
 * <p>Built once at {@code start()}: every {@code @CommandHandler} of a registered class becomes the route of its
 * {@code @Command} parameter's exact type.</p>
 * <ul>
 *   <li><em>Service</em> method (host not {@code @Aggregate}): invoked on the host instance from the
 *       {@link HandlerInstanceProvider}.</li>
 *   <li><em>Aggregate instance</em> method: the target is loaded through {@link AggregateAccess} by the command's
 *       {@code @AggregateIdentifier} (absent → {@link AggregateNotFoundException}), the handler invoked, the
 *       aggregate saved.</li>
 *   <li><em>Creation</em>: an {@code @Aggregate} constructor or static factory; the new aggregate is saved.</li>
 * </ul>
 * <p>The engine supplies the {@code @Command} argument; every other parameter comes from the first
 * {@link HandlerParameterResolver} that {@linkplain HandlerParameterResolver#supports supports} it, chosen at
 * {@code start()} and asked to {@linkplain HandlerParameterResolver#resolve resolve} before each invocation
 * (FR-019). A parameter no resolver supports fails with {@link IllegalStateException} naming handler and
 * parameter; US6 rejects such a model at startup.</p>
 *
 * <p>A {@code CommandId} parameter is supplied by the engine itself, before any host resolver is asked: it is the
 * identity of the command being handled (005 contracts/command-identity.md).</p>
 *
 * <h2>{@link #send(Object)}</h2>
 * <ul>
 *   <li>Inside an engine unit of work bound on this thread: runs the route synchronously in it. Any failure
 *       ({@code null} or non-{@code @Command} command → {@link IllegalArgumentException}, no route →
 *       {@link NoCommandHandlerException}, or an exception of the handler, loader, saver, resolver, provider or
 *       parameter resolver) first marks the current {@link LocalTransaction} rollback-only and reports itself to
 *       the unit of work, then leaves {@code send} unchanged (002 FR-012). Before routing, the unit of work assigns
 *       the command its identity; a command whose identity already committed for the instance is not handled
 *       again ({@code saga.command.skipped}, 005 FR-004), and a handled one is recorded with the running step.</li>
 *   <li>Outside one (including a thread a handler started): validates the command, then runs the route in a new
 *       {@link NonSagaUnitOfWork} (FR-016).</li>
 * </ul>
 *
 * <p>Immutable; thread-safe if the host plug-ins and client handlers are.</p>
 */
final class EngineCommandGateway implements CommandGateway {

    /** How one command type reaches its handler. */
    @FunctionalInterface
    private interface CommandRoute {

        void handle(Object command);
    }

    /**
     * Supplies a {@code CommandId} handler parameter: the identity of the command being handled. Consulted before
     * the host's resolvers (005 research §2).
     */
    private static final HandlerParameterResolver COMMAND_ID = new HandlerParameterResolver() {

        @Override
        public boolean supports(Executable handler, Parameter parameter) {
            return parameter.getType() == CommandId.class;
        }

        @Override
        public Object resolve(Executable handler, Parameter parameter) {
            return CommandIds.bound().orElseThrow(() -> new IllegalStateException(
                    "No command is being handled on this thread for the CommandId parameter of " + handler));
        }
    };

    private final Map<Class<?>, CommandRoute> routes;
    private final TransactionBoundary transactions;
    private final boolean outboxStoresCommands;
    private final Supplier<NonSagaUnitOfWork> nonSagaUnitsOfWork;
    private final Diagnostics diagnostics;

    private EngineCommandGateway(Map<Class<?>, CommandRoute> routes, TransactionBoundary transactions,
            boolean outboxStoresCommands, Supplier<NonSagaUnitOfWork> nonSagaUnitsOfWork, Diagnostics diagnostics) {
        this.routes = Map.copyOf(routes);
        this.transactions = transactions;
        this.outboxStoresCommands = outboxStoresCommands;
        this.nonSagaUnitsOfWork = nonSagaUnitsOfWork;
        this.diagnostics = diagnostics;
    }

    /**
     * Whether {@code parameter} of {@code handler} is the engine-supplied {@code CommandId} (005 FR-003); startup
     * checks count it as resolvable.
     */
    static boolean isCommandIdParameter(Executable handler, Parameter parameter) {
        return COMMAND_ID.supports(handler, parameter);
    }

    /**
     * Builds the routing table of {@code registered}; {@code diagnostics} is where non-saga units of work log.
     *
     * @throws IllegalStateException if two handlers take the same command type ({@code validateAll} rejects that
     *                               first)
     */
    static EngineCommandGateway of(Collection<Class<?>> registered, AggregateAccess access, EngineSettings settings,
            Diagnostics diagnostics) {
        RouteFactory factory = new RouteFactory(access, settings);
        Map<Class<?>, CommandRoute> routes = new LinkedHashMap<>();
        for (Class<?> host : registered) {
            factory.routesOf(host).forEach((commandType, route) -> addRoute(routes, commandType, route));
        }
        return new EngineCommandGateway(routes, settings.transactions(), settings.outbox().supportsCommands(),
                () -> new NonSagaUnitOfWork(settings,
                diagnostics, settings.sources().newUnitOfWorkId()), diagnostics);
    }

    @Override
    public void send(Object command) {
        Optional<UnitOfWork> bound = UnitOfWork.bound();
        if (bound.isPresent()) {
            sendWithin(bound.get(), command);
            return;
        }
        requireCommand(command);
        nonSagaUnitsOfWork.get().run(() -> send(command));
    }

    private void sendWithin(UnitOfWork unitOfWork, Object command) {
        try {
            requireCommand(command);
            CommandRoute route = routeFor(command);
            boolean logged = isOutbox(command);
            if (logged && !outboxStoresCommands) {
                throw new IllegalStateException("Command " + command.getClass().getName() + " is delivered OUTBOX, "
                        + "but the engine's Outbox does not support commands; use a transport whose outbox does, "
                        + "such as the in-memory transport");
            }
            CommandId id = unitOfWork.nextCommandId(command.getClass());
            if (unitOfWork.alreadyHandled(id)) {
                diagnostics.commandSkipped(id);
                return;
            }
            if (logged) {
                // 007 research §3: stored with the unit of work, handled after it commits, never if it rolls back.
                unitOfWork.recordOutboxCommand(new OutgoingCommand(id, command));
                unitOfWork.commandHandled(id);
                return;
            }
            CommandIds.withBound(id, () -> {
                route.handle(command);
                return null;
            });
            unitOfWork.commandHandled(id);
        } catch (RuntimeException | Error failure) {
            transactions.current().ifPresent(LocalTransaction::setRollbackOnly);
            unitOfWork.failed(failure);
            throw failure;
        }
    }

    /**
     * Handles a outbox command delivered by a transport ({@code CommandReceiver}, 007 research §3): a new non-saga
     * unit of work, the handler with the delivery's {@code CommandId} bound, then {@code inTransaction} before commit.
     *
     * @throws IllegalStateException if a unit of work is running on this thread
     */
    void receive(OutboxCommand delivery) {
        if (delivery == null) {
            throw new IllegalArgumentException("delivery must not be null");
        }
        Object command = delivery.command();
        requireCommand(command);
        if (UnitOfWork.bound().isPresent()) {
            throw new IllegalStateException("CommandReceiver.receive was called inside a unit of work; a transport "
                    + "delivers outbox commands from its own threads");
        }
        CommandRoute route = routeFor(command);
        nonSagaUnitsOfWork.get().run(() -> {
            CommandIds.withBound(delivery.commandId(), () -> {
                route.handle(command);
                return null;
            });
            LocalTransaction transaction = transactions.current().orElseThrow(() -> new IllegalStateException(
                    "no transaction is active in the unit of work handling " + delivery.commandId()));
            delivery.inTransaction().accept(transaction);
        });
    }

    private static boolean isOutbox(Object command) {
        return command.getClass().getAnnotation(Command.class).delivery() == CommandDelivery.OUTBOX;
    }

    private CommandRoute routeFor(Object command) {
        CommandRoute route = routes.get(command.getClass());
        if (route == null) {
            throw new NoCommandHandlerException(command.getClass());
        }
        return route;
    }

    private static void requireCommand(Object command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (!command.getClass().isAnnotationPresent(Command.class)) {
            throw new IllegalArgumentException(command.getClass().getName() + " is not annotated @Command");
        }
    }

    private static void addRoute(Map<Class<?>, CommandRoute> routes, Class<?> commandType, CommandRoute route) {
        if (routes.putIfAbsent(commandType, route) != null) {
            throw new IllegalStateException("Command " + commandType.getName() + " has more than one @CommandHandler");
        }
    }

    /** Turns the {@code @CommandHandler} members of one host class into routes keyed by command type. */
    private static final class RouteFactory {

        private final AggregateAccess access;
        private final HandlerInstanceProvider instances;
        private final List<HandlerParameterResolver> parameterResolvers;

        private RouteFactory(AggregateAccess access, EngineSettings settings) {
            this.access = access;
            this.instances = settings.handlerInstances();
            this.parameterResolvers = settings.parameterResolvers();
        }

        Map<Class<?>, CommandRoute> routesOf(Class<?> host) {
            Map<Class<?>, CommandRoute> routes = new LinkedHashMap<>();
            for (Constructor<?> constructor : Reflection.annotatedConstructors(host, CommandHandler.class)) {
                HandlerArguments arguments = argumentsOf(constructor);
                routes.put(arguments.commandType(),
                        creation(arguments, values -> Reflection.construct(constructor, values)));
            }
            for (Method method : Reflection.annotatedMethods(host, CommandHandler.class)) {
                HandlerArguments arguments = argumentsOf(method);
                routes.put(arguments.commandType(), methodRoute(host, method, arguments));
            }
            return routes;
        }

        private CommandRoute methodRoute(Class<?> host, Method method, HandlerArguments arguments) {
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            if (host.isAnnotationPresent(Aggregate.class)) {
                return isStatic
                        ? creation(arguments, values -> Reflection.invoke(method, null, values))
                        : aggregateInstance(host, method, arguments);
            }
            return command -> Reflection.invoke(method, isStatic ? null : instances.instanceOf(host),
                    arguments.forCommand(command));
        }

        private CommandRoute aggregateInstance(Class<?> aggregateType, Method method, HandlerArguments arguments) {
            return command -> {
                Object id = Reflection.aggregateIdentifier(command);
                Object aggregate = access.load(aggregateType, id)
                        .orElseThrow(() -> new AggregateNotFoundException(aggregateType, id));
                Reflection.invoke(method, aggregate, arguments.forCommand(command));
                access.save(aggregate);
            };
        }

        private CommandRoute creation(HandlerArguments arguments, Function<Object[], Object> creator) {
            return command -> {
                Object created = creator.apply(arguments.forCommand(command));
                if (created == null) {
                    throw new IllegalStateException("Creation @CommandHandler " + arguments.handlerName()
                            + " returned null");
                }
                access.save(created);
            };
        }

        private HandlerArguments argumentsOf(Executable handler) {
            return HandlerArguments.of(handler, parameterResolvers);
        }
    }

    /**
     * The argument list of one handler: the command at its {@code @Command} position, every other position from
     * the resolver chosen for it at {@code start()}.
     */
    private static final class HandlerArguments {

        private final Executable handler;
        private final int commandPosition;
        private final List<Optional<HandlerParameterResolver>> resolvers;

        private HandlerArguments(Executable handler, int commandPosition,
                List<Optional<HandlerParameterResolver>> resolvers) {
            this.handler = handler;
            this.commandPosition = commandPosition;
            this.resolvers = resolvers;
        }

        static HandlerArguments of(Executable handler, List<HandlerParameterResolver> candidates) {
            Parameter[] parameters = handler.getParameters();
            int commandPosition = -1;
            List<Optional<HandlerParameterResolver>> resolvers = new ArrayList<>();
            for (int position = 0; position < parameters.length; position++) {
                Parameter parameter = parameters[position];
                if (commandPosition < 0 && parameter.getType().isAnnotationPresent(Command.class)) {
                    commandPosition = position;
                    resolvers.add(Optional.empty());
                } else if (COMMAND_ID.supports(handler, parameter)) {
                    resolvers.add(Optional.of(COMMAND_ID));
                } else {
                    resolvers.add(candidates.stream()
                            .filter(candidate -> candidate.supports(handler, parameter))
                            .findFirst());
                }
            }
            if (commandPosition < 0) {
                throw new IllegalStateException("@CommandHandler " + handler + " declares no @Command parameter");
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
                values[position] = position == commandPosition ? command : resolve(position, parameters[position]);
            }
            return values;
        }

        private Object resolve(int position, Parameter parameter) {
            HandlerParameterResolver resolver = resolvers.get(position).orElseThrow(() -> new IllegalStateException(
                    "@CommandHandler " + handlerName() + " parameter #" + (position + 1) + " "
                            + parameter.getType().getSimpleName() + " cannot be resolved: no HandlerParameterResolver"
                            + " supports it"));
            return resolver.resolve(handler, parameter);
        }
    }
}
