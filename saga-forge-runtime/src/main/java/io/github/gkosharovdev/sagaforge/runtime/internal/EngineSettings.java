package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * The host plug-ins and defaults one engine runs with, as collected by {@code SagaRuntime.Builder}
 * (data-model.md "Plug-ins").
 *
 * <p><strong>Not API.</strong> Public only so that {@code SagaRuntime} can hand it to {@link EngineRuntime}.</p>
 *
 * <p>Immutable (the lists are copied); thread-safe if the plug-ins are.</p>
 *
 * @param storage            instance store; joins the unit of work's transaction
 * @param contextStore       {@code SagaContext} store; never joins it
 * @param transactions       the host's local transactions
 * @param dispatcher         outbound transport
 * @param handlerInstances   objects hosting service handlers, loaders and savers; {@link #noHandlerInstances()} if
 *                           no registered class needs one
 * @param aggregateResolvers host resolvers, first supporting wins
 * @param parameterResolvers host resolvers of non-command handler parameters, first supporting wins
 * @param sagaObjects        the host's saga-object provider, if any
 * @param outbox            receives committed units of work's buffered events
 * @param outboxTiming      {@code outbox.timing()}, read once when the engine was built
 * @param sources            clock and id suppliers
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
        RetrySettings retry) {

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
        aggregateResolvers = copyOf(aggregateResolvers, "aggregateResolvers");
        parameterResolvers = copyOf(parameterResolvers, "parameterResolvers");
    }

    /**
     * The provider used when no registered class hosts a service handler, loader or saver; it is never asked, and
     * fails with {@link IllegalStateException} if it is.
     */
    /** Settings with {@link RetrySettings#defaults()}. */
    public EngineSettings(StorageProvider storage, SagaContextStore contextStore, TransactionBoundary transactions,
            EventDispatcher dispatcher, HandlerInstanceProvider handlerInstances,
            List<AggregateResolver> aggregateResolvers, List<HandlerParameterResolver> parameterResolvers,
            Optional<SagaObjectProvider> sagaObjects, Outbox outbox, Outbox.Timing outboxTiming,
            TimeAndIdentity sources) {
        this(storage, contextStore, transactions, dispatcher, handlerInstances, aggregateResolvers,
                parameterResolvers, sagaObjects, outbox, outboxTiming, sources, RetrySettings.defaults());
    }

    public static HandlerInstanceProvider noHandlerInstances() {
        return handlerType -> {
            throw new IllegalStateException("No HandlerInstanceProvider was registered; cannot supply "
                    + handlerType.getName());
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
