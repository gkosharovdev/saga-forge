package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link CommandGateway} of the saga test harness: routes each command to the single {@code @CommandHandler}
 * for its exact class and runs it synchronously inside the current saga-scoped unit of work
 * (contracts/command-gateway.md).
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <h2>Routes</h2>
 * <p>Each command type maps to one {@link Route}. Every handler must have exactly one parameter, whose type is
 * {@code @Command}-annotated (the only form the harness supports).</p>
 * <ul>
 *   <li><em>Service-hosted</em> ({@link #registerServiceHandlers(Class, HandlerInstanceProvider)}): non-static
 *       {@code @CommandHandler} methods of a non-{@code @Aggregate} class; the target is obtained from the
 *       {@link HandlerInstanceProvider} on every send.</li>
 *   <li><em>Aggregate-hosted</em> ({@code registerAggregateHandlers}, used by
 *       {@link SagaTestHarness.Builder#aggregate(Class)}): instance-method handlers load the target by the
 *       command's {@code @AggregateIdentifier} (client {@code @AggregateLoader} first, else the
 *       {@code AggregateResolver}) and save it afterwards; creation handlers (constructors and static factories)
 *       save the new aggregate. Saving: client {@code @AggregateSaver}, else a supporting resolver, else
 *       nothing. See {@link HarnessAggregateAccess}.</li>
 *   <li>Further routes can be added through {@link #register(Class, Route)}.</li>
 * </ul>
 *
 * <h2>{@link #send(Object)}</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} for a {@code null} or non-{@code @Command} command;</li>
 *   <li>{@link NoCommandHandlerException} if no route exists for the command's class;</li>
 *   <li>{@link io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException} if the loader returns
 *       {@code null} or the resolver finds no aggregate for the command's identifier;</li>
 *   <li>{@link IllegalStateException} if no harness unit of work is active (non-saga units of work are not
 *       modelled by the harness);</li>
 *   <li>any exception thrown by the handler, a loader or saver, or the resolver propagates unchanged
 *       (reflection wrappers are removed).</li>
 * </ul>
 * <p>Whatever the exception, the active unit of work is marked rollback-only <em>before</em> it leaves
 * {@code send}, so a step that catches it cannot prevent the rollback (FR-012).</p>
 */
public final class HarnessCommandGateway implements CommandGateway {

    /** How a command reaches its handler. */
    public interface Route {
        /** Runs the handler for {@code command}; exceptions propagate unchanged. */
        void dispatch(Object command);

        /** Human-readable handler description, for error messages. */
        String describe();
    }

    private final Map<Class<?>, Route> routes = new LinkedHashMap<>();
    private final Supplier<HarnessUnitOfWork> currentUnitOfWork;

    /**
     * @param currentUnitOfWork returns the active unit of work, or {@code null} if none is active
     */
    HarnessCommandGateway(Supplier<HarnessUnitOfWork> currentUnitOfWork) {
        this.currentUnitOfWork = currentUnitOfWork;
    }

    /**
     * Routes {@code commandType} to {@code route}.
     *
     * @throws IllegalArgumentException if {@code commandType} is not {@code @Command}-annotated or already
     *                                  has a route (FR-011)
     */
    public void register(Class<?> commandType, Route route) {
        if (commandType == null || route == null) {
            throw new IllegalArgumentException("commandType and route must not be null");
        }
        if (!commandType.isAnnotationPresent(Command.class)) {
            throw new IllegalArgumentException(commandType.getName() + " is not annotated @Command");
        }
        Route existing = routes.putIfAbsent(commandType, route);
        if (existing != null) {
            throw new IllegalArgumentException("Command " + commandType.getName() + " is handled by both "
                    + existing.describe() + " and " + route.describe());
        }
    }

    /**
     * Registers a route for every {@code @CommandHandler} method of the service class {@code serviceType}. The
     * handler target is {@code instances.instanceOf(serviceType)}, looked up on each send.
     *
     * @throws IllegalArgumentException if {@code serviceType} is an {@code @Aggregate}, or a handler method is
     *                                  static or does not have exactly one {@code @Command}-typed parameter
     *                                  (the only form the harness supports), or a command type already has a
     *                                  route
     */
    public void registerServiceHandlers(Class<?> serviceType, HandlerInstanceProvider instances) {
        for (Method method : HarnessReflection.methods(serviceType)) {
            if (!method.isAnnotationPresent(CommandHandler.class)) {
                continue;
            }
            if (serviceType.isAnnotationPresent(Aggregate.class)) {
                throw new IllegalArgumentException("Aggregate-hosted @CommandHandler " + method
                        + " is not supported by service registration");
            }
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isAnnotationPresent(Command.class)) {
                throw new IllegalArgumentException("Unsupported service @CommandHandler " + method
                        + ": the harness supports non-static methods with exactly one @Command parameter");
            }
            register(method.getParameterTypes()[0], new ServiceHandlerRoute(serviceType, method, instances));
        }
    }

    /**
     * Registers a route for every {@code @CommandHandler} of the {@code @Aggregate} class {@code aggregateType}:
     * <ul>
     *   <li><b>Instance method</b>: loads the target by the command's {@code @AggregateIdentifier} value through
     *       {@code access} (client {@code @AggregateLoader}, else resolver; absent → {@code AggregateNotFoundException}),
     *       invokes the method, then saves the aggregate.</li>
     *   <li><b>Creation handler</b> (constructor, or static factory returning the aggregate type): invokes it, then
     *       saves the new aggregate.</li>
     * </ul>
     * <p>Saving goes through {@code access}: client {@code @AggregateSaver}, else a supporting resolver, else
     * nothing. A handler that throws is not followed by a save.</p>
     *
     * <p><b>Startup check</b> (contracts/command-gateway.md rule 6, research §3): an aggregate with instance-method
     * handlers but neither a client loader nor a supporting resolver is rejected here, since the scanner cannot see
     * host resolvers.</p>
     *
     * @throws IllegalArgumentException if {@code aggregateType} is not {@code @Aggregate}-annotated, a handler does
     *                                  not have exactly one {@code @Command}-typed parameter, a static factory does
     *                                  not return the aggregate type, the command of an instance-method handler
     *                                  declares no {@code @AggregateIdentifier}, the startup check fails, or a
     *                                  command type already has a route
     */
    void registerAggregateHandlers(Class<?> aggregateType, HarnessAggregateAccess access) {
        if (!aggregateType.isAnnotationPresent(Aggregate.class)) {
            throw new IllegalArgumentException(aggregateType.getName() + " is not annotated @Aggregate");
        }
        boolean hasInstanceHandlers = false;
        for (Constructor<?> constructor : aggregateType.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(CommandHandler.class)) {
                Class<?> commandType = singleCommandParameter(constructor.getParameterTypes(), constructor);
                register(commandType, new CreationRoute(aggregateType, access,
                        command -> HarnessReflection.construct(constructor, command),
                        aggregateType.getName() + "#<init>"));
            }
        }
        for (Method method : HarnessReflection.methods(aggregateType)) {
            if (!method.isAnnotationPresent(CommandHandler.class)) {
                continue;
            }
            Class<?> commandType = singleCommandParameter(method.getParameterTypes(), method);
            if (Modifier.isStatic(method.getModifiers())) {
                if (!aggregateType.isAssignableFrom(method.getReturnType())) {
                    throw new IllegalArgumentException("Static @CommandHandler " + method + " must return "
                            + aggregateType.getName() + " to be a creation handler");
                }
                register(commandType, new CreationRoute(aggregateType, access,
                        command -> HarnessReflection.invoke(method, null, command),
                        aggregateType.getName() + "#" + method.getName()));
            } else {
                if (!HarnessReflection.declaresAggregateIdentifier(commandType)) {
                    throw new IllegalArgumentException("Command " + commandType.getName() + " of instance-method"
                            + " @CommandHandler " + method + " declares no @AggregateIdentifier (FR-014)");
                }
                hasInstanceHandlers = true;
                register(commandType, new InstanceHandlerRoute(aggregateType, method, access));
            }
        }
        if (hasInstanceHandlers && !access.canLoad(aggregateType)) {
            throw new IllegalArgumentException("@Aggregate " + aggregateType.getName() + " has instance-method"
                    + " @CommandHandler methods but no @AggregateLoader and no AggregateResolver supporting it");
        }
    }

    private static Class<?> singleCommandParameter(Class<?>[] parameterTypes, Object handler) {
        if (parameterTypes.length != 1 || !parameterTypes[0].isAnnotationPresent(Command.class)) {
            throw new IllegalArgumentException("Unsupported aggregate @CommandHandler " + handler
                    + ": the harness supports exactly one @Command parameter");
        }
        return parameterTypes[0];
    }

    /** Command types with a route, in registration order. */
    public Set<Class<?>> commandTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(routes.keySet()));
    }

    @Override
    public void send(Object command) {
        HarnessUnitOfWork unitOfWork = currentUnitOfWork.get();
        try {
            if (command == null) {
                throw new IllegalArgumentException("command must not be null");
            }
            if (!command.getClass().isAnnotationPresent(Command.class)) {
                throw new IllegalArgumentException(command.getClass().getName() + " is not annotated @Command");
            }
            if (unitOfWork == null) {
                throw new IllegalStateException(
                        "No harness unit of work is active; cannot send " + command.getClass().getName());
            }
            Route route = routes.get(command.getClass());
            if (route == null) {
                throw new NoCommandHandlerException(command.getClass());
            }
            route.dispatch(command);
        } catch (Throwable failure) {
            if (unitOfWork != null) {
                unitOfWork.markRollbackOnly(failure);
            }
            throw HarnessReflection.sneakyThrow(failure);
        }
    }

    /** Service-hosted handler: invokes {@code method} on the provider's instance of {@code serviceType}. */
    private record ServiceHandlerRoute(Class<?> serviceType, Method method, HandlerInstanceProvider instances)
            implements Route {

        @Override
        public void dispatch(Object command) {
            HarnessReflection.invoke(method, instances.instanceOf(serviceType), command);
        }

        @Override
        public String describe() {
            return serviceType.getName() + "#" + method.getName();
        }
    }

    /** Instance-method handler of an aggregate: load by the command's identifier, invoke, save. */
    private record InstanceHandlerRoute(Class<?> aggregateType, Method method, HarnessAggregateAccess access)
            implements Route {
        @Override
        public void dispatch(Object command) {
            Object id = HarnessReflection.aggregateIdentifier(command);
            Object aggregate = access.load(aggregateType, id);
            HarnessReflection.invoke(method, aggregate, command);
            access.save(aggregateType, aggregate);
        }

        @Override
        public String describe() {
            return aggregateType.getName() + "#" + method.getName();
        }
    }

    /** Creation handler (constructor or static factory): invoke, then save the new aggregate. */
    private record CreationRoute(Class<?> aggregateType, HarnessAggregateAccess access,
            Function<Object, Object> creator, String description) implements Route {
        @Override
        public void dispatch(Object command) {
            Object created = creator.apply(command);
            if (created == null) {
                throw new IllegalStateException("Creation handler " + description + " returned null");
            }
            access.save(aggregateType, created);
        }

        @Override
        public String describe() {
            return description;
        }
    }
}
