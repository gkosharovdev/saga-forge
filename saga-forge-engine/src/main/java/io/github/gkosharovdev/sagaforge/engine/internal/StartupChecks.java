package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The engine's own startup checks, run by {@code SagaEngine.start()} next to {@code validateAll}
 * (contracts/engine-startup.md "Engine checks and messages", FR-019, FR-034, FR-035, Properties 21 to 23).
 *
 * <ul>
 *   <li><b>Aggregate loading</b> (Property 21): every {@code @Aggregate} with an instance-method
 *       {@code @CommandHandler} has a registered {@code @AggregateLoader} for it or a registered
 *       {@code AggregateResolver} that supports it.</li>
 *   <li><b>Handler parameters</b> (Property 22): every {@code @CommandHandler} parameter other than its
 *       {@code @Command} one is supported by at least one {@code HandlerParameterResolver}.</li>
 *   <li><b>Saga objects</b> (Property 23): with a {@link SagaObjectProvider} registered, it supports every
 *       {@code @Saga} class; without one, every {@code @Saga} class declares a no-argument constructor (any
 *       visibility).</li>
 * </ul>
 *
 * <p>All errors are collected in one pass, in registration order. Only {@code supports} methods are called: no saga
 * object, handler, loader or saver host instance is created (spec Assumptions "Handler instances"). Members the
 * scanner rejects (a handler without a {@code @Command} parameter, say) are skipped here; {@code validateAll} reports
 * them.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
final class StartupChecks {

    private StartupChecks() {
    }

    /**
     * Every engine error of {@code registered} with {@code settings}' plug-ins.
     *
     * @param registered the registered classes, in registration order
     * @param settings   the host plug-ins
     * @return the error messages, in registration order; empty if the model is runnable
     */
    static List<String> errorsOf(Collection<Class<?>> registered, EngineSettings settings) {
        Set<Class<?>> loadedTypes = loaderTypes(registered);
        List<String> errors = new ArrayList<>();
        for (Class<?> type : registered) {
            if (type.isAnnotationPresent(Saga.class)) {
                sagaObjectError(type, settings).ifPresent(errors::add);
                errors.addAll(retryBackoffErrors(type, settings));
                continue;
            }
            if (type.isAnnotationPresent(Aggregate.class) && hasInstanceHandler(type)
                    && !loadedTypes.contains(type) && !resolverSupports(type, settings)) {
                errors.add("@Aggregate " + type.getSimpleName() + " has instance-method @CommandHandler methods but"
                        + " no @AggregateLoader and no AggregateResolver supporting it");
            }
            handlers(type).forEach(handler -> errors.addAll(unresolvableParameters(handler, settings)));
            if (!settings.outbox().supportsCommands()) {
                handlers(type).forEach(handler -> loggedCommandError(handler).ifPresent(errors::add));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * An error if {@code handler} handles a {@code @Command(delivery = LOGGED)} type while the outbox cannot store
     * commands ({@code specs/007-in-memory-transport} research §3, tasks.md T056).
     */
    private static Optional<String> loggedCommandError(Executable handler) {
        int position = commandPosition(handler.getParameters());
        if (position < 0) {
            return Optional.empty();
        }
        Class<?> commandType = handler.getParameters()[position].getType();
        if (commandType.getAnnotation(Command.class).delivery() != CommandDelivery.LOGGED) {
            return Optional.empty();
        }
        return Optional.of("Command " + commandType.getSimpleName() + " handled by @CommandHandler "
                + handlerName(handler) + " is delivered LOGGED, but the Outbox does not support commands");
    }

    /**
     * One error per {@code @SagaStep} whose retry policy waits longer in total than the engine allows (005 FR-014a);
     * steps in method-name order. A policy the scanner rejects (R19) waits no positive total and adds nothing here.
     */
    private static List<String> retryBackoffErrors(Class<?> sagaType, EngineSettings settings) {
        long max = settings.retry().maxTotalBackoff().toMillis();
        return Reflection.annotatedMethods(sagaType, SagaStep.class).stream()
                .sorted(java.util.Comparator.comparing(Method::getName))
                .filter(method -> Backoff.total(method.getAnnotation(SagaStep.class).retry()) > max)
                .map(method -> "@SagaStep " + sagaType.getSimpleName() + "." + method.getName()
                        + ": total retry backoff " + Backoff.total(method.getAnnotation(SagaStep.class).retry())
                        + " ms exceeds the engine maximum " + max + " ms")
                .toList();
    }

    private static Optional<String> sagaObjectError(Class<?> sagaType, EngineSettings settings) {
        if (settings.sagaObjects().isPresent()) {
            SagaObjectProvider provider = settings.sagaObjects().get();
            return provider.supports(sagaType) ? Optional.empty()
                    : Optional.of("@Saga class " + sagaType.getSimpleName()
                            + " is not supported by the registered SagaObjectProvider");
        }
        return Reflection.noArgumentConstructor(sagaType).isPresent() ? Optional.empty()
                : Optional.of("@Saga class " + sagaType.getSimpleName() + " must declare a no-argument"
                        + " constructor, or a SagaObjectProvider supporting it must be registered");
    }

    /** The aggregate types some registered class has an {@code @AggregateLoader} for. */
    private static Set<Class<?>> loaderTypes(Collection<Class<?>> registered) {
        return registered.stream()
                .flatMap(type -> Reflection.annotatedMethods(type, AggregateLoader.class).stream())
                .map(Method::getReturnType)
                .collect(Collectors.toSet());
    }

    private static boolean hasInstanceHandler(Class<?> aggregateType) {
        return Reflection.annotatedMethods(aggregateType, CommandHandler.class).stream()
                .anyMatch(method -> !Modifier.isStatic(method.getModifiers()));
    }

    private static boolean resolverSupports(Class<?> aggregateType, EngineSettings settings) {
        return settings.aggregateResolvers().stream().anyMatch(resolver -> resolver.supports(aggregateType));
    }

    private static Stream<Executable> handlers(Class<?> host) {
        return Stream.concat(Reflection.annotatedConstructors(host, CommandHandler.class).stream(),
                Reflection.annotatedMethods(host, CommandHandler.class).stream());
    }

    /** One error per non-command parameter of {@code handler} that no parameter resolver supports. */
    private static List<String> unresolvableParameters(Executable handler, EngineSettings settings) {
        Parameter[] parameters = handler.getParameters();
        int commandPosition = commandPosition(parameters);
        if (commandPosition < 0) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        List<Integer> commandIdPositions = new ArrayList<>();
        for (int position = 0; position < parameters.length; position++) {
            Parameter parameter = parameters[position];
            boolean commandId = position != commandPosition
                    && EngineCommandGateway.isCommandIdParameter(handler, parameter);
            if (commandId) {
                commandIdPositions.add(position + 1);
            }
            boolean supported = position == commandPosition || commandId || settings.parameterResolvers().stream()
                    .anyMatch(resolver -> resolver.supports(handler, parameter));
            if (!supported) {
                errors.add("@CommandHandler " + handlerName(handler) + " parameter #" + (position + 1) + " "
                        + parameter.getType().getSimpleName()
                        + " cannot be resolved: no HandlerParameterResolver supports it");
            }
        }
        if (commandIdPositions.size() > 1) {
            errors.add("@CommandHandler " + handlerName(handler) + " declares more than one CommandId parameter: #"
                    + commandIdPositions.stream().map(String::valueOf).collect(Collectors.joining(", #")));
        }
        return errors;
    }

    /** The position of the first {@code @Command}-typed parameter, as the command gateway binds it; -1 if none. */
    private static int commandPosition(Parameter[] parameters) {
        for (int position = 0; position < parameters.length; position++) {
            if (parameters[position].getType().isAnnotationPresent(Command.class)) {
                return position;
            }
        }
        return -1;
    }

    /** {@code Class.method}, or {@code Class.Class} for a constructor, as the command gateway names handlers. */
    private static String handlerName(Executable handler) {
        String owner = handler.getDeclaringClass().getSimpleName();
        return owner + "." + (handler instanceof Constructor<?> ? owner : handler.getName());
    }
}
