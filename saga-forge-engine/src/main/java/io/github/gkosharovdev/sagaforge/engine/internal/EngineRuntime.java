package io.github.gkosharovdev.sagaforge.engine.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;

/**
 * The assembled runtime of one started engine: its saga models, command routes, event router and publisher.
 *
 * <p><strong>Not API.</strong> The composition root behind {@code SagaEngine}, public only because the engine's
 * public types live in another package; everything it assembles stays package-private. {@code SagaEngine} assembles
 * it only after {@code validateAll} accepted the registered classes, and owns the started/refused state.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are.</p>
 */
public final class EngineRuntime {

    private final List<SagaModel> sagas;
    private final EventRouter router;
    private final CommandGateway commandGateway;
    private final EventPublisher eventPublisher;

    private EngineRuntime(List<SagaModel> sagas, EventRouter router, CommandGateway commandGateway,
            EventPublisher eventPublisher) {
        this.sagas = sagas;
        this.router = router;
        this.commandGateway = commandGateway;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Builds the models and routes of {@code registered}, which {@code validateAll} has accepted.
     *
     * @param registered  the registered classes, in registration order
     * @param settings    the host plug-ins and defaults
     * @param diagnostics where the runtime logs
     */
    public static EngineRuntime assemble(Collection<Class<?>> registered, EngineSettings settings,
            Diagnostics diagnostics) {
        List<SagaModel> sagas = registered.stream()
                .filter(type -> type.isAnnotationPresent(Saga.class))
                .map(SagaModel::of)
                .toList();
        AggregateAccess access = AggregateAccess.of(registered, settings.handlerInstances(),
                settings.aggregateResolvers());
        EngineCommandGateway gateway = EngineCommandGateway.of(registered, access, settings, diagnostics);
        EventRouter router = new EventRouter(sagas, new SagaUnitOfWork.Collaborators(settings, gateway, diagnostics));
        return new EngineRuntime(sagas, router, gateway, new EngineEventPublisher(settings.dispatcher()));
    }

    /**
     * {@code true} if a registered class hosts a service {@code @CommandHandler} method, an
     * {@code @AggregateLoader} or an {@code @AggregateSaver} instance method, so a {@code HandlerInstanceProvider}
     * must supply its instances.
     */
    public static boolean needsHandlerInstances(Collection<Class<?>> registered) {
        return registered.stream().anyMatch(EngineRuntime::needsHostInstance);
    }

    /**
     * The engine's own startup errors of {@code registered} with {@code settings}' plug-ins
     * (contracts/engine-startup.md "Engine checks and messages"); empty if the model is runnable. Calls only
     * {@code supports} methods.
     *
     * @param registered the registered classes, in registration order
     * @param settings   the host plug-ins
     */
    public static List<String> startupErrors(Collection<Class<?>> registered, EngineSettings settings) {
        return StartupChecks.errorsOf(registered, settings);
    }

    /** The registered {@code @Saga} classes, in registration order. */
    public List<Class<?>> sagaTypes() {
        return sagas.stream().<Class<?>>map(SagaModel::type).toList();
    }

    /** Handles one inbound domain event (contracts/event-receiver.md). */
    public HandlingReport receive(Object domainEvent) {
        return router.receive(domainEvent);
    }

    /** The engine's command gateway, also the one injected into saga objects. */
    public CommandGateway commandGateway() {
        return commandGateway;
    }

    /** The engine's event publisher. */
    public EventPublisher eventPublisher() {
        return eventPublisher;
    }

    private static boolean needsHostInstance(Class<?> type) {
        if (type.isAnnotationPresent(Saga.class)) {
            return false;
        }
        boolean serviceHandlers = !type.isAnnotationPresent(Aggregate.class)
                && hasInstanceMethod(type, CommandHandler.class);
        return serviceHandlers
                || hasInstanceMethod(type, AggregateLoader.class)
                || hasInstanceMethod(type, AggregateSaver.class);
    }

    private static boolean hasInstanceMethod(Class<?> type, Class<? extends Annotation> marker) {
        return Reflection.annotatedMethods(type, marker).stream()
                .anyMatch(method -> !Modifier.isStatic(method.getModifiers()));
    }
}
