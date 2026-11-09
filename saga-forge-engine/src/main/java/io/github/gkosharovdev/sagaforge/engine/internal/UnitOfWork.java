package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;

/**
 * An engine unit of work, saga-scoped ({@link SagaUnitOfWork}) or not ({@link NonSagaUnitOfWork}), as bound to its
 * thread through {@link DomainEventScope#runWith(DomainEventRecorder, Runnable)}.
 *
 * <p>Besides recording domain events it accepts failure reports: the command gateway reports every handler,
 * loader, saver, resolver or provider failure before the exception leaves {@code send}, so the unit of work fails
 * even if the step or handler that called {@code send} catches the exception (002 FR-012).</p>
 */
interface UnitOfWork extends DomainEventRecorder {

    /** Reports a failure inside this unit of work; the first report is kept and fails it. */
    void failed(Throwable failure);

    /**
     * The identity of the next command of {@code commandType} sent in this unit of work (005 FR-001). The default,
     * for units of work outside a saga, is a fresh {@linkplain CommandId#unscoped(String) unscoped} identity.
     */
    default CommandId nextCommandId(Class<?> commandType) {
        return CommandId.unscoped(commandType.getName());
    }

    /**
     * Whether handling of {@code id} already committed for this unit of work's instance (005 FR-004); its handler
     * must then not be invoked again. Always {@code false} outside a saga.
     */
    default boolean alreadyHandled(CommandId id) {
        return false;
    }

    /** Records that {@code id}'s handler returned in this unit of work; it commits with the running step. */
    default void commandHandled(CommandId id) {
    }

    /** The engine unit of work bound to the calling thread, if any; another recorder is not one. */
    static Optional<UnitOfWork> bound() {
        return DomainEventScope.current().filter(UnitOfWork.class::isInstance).map(UnitOfWork.class::cast);
    }

    /**
     * Rethrows {@code failure} unchanged if it is an {@link Error}; returns it for the caller to throw if it is a
     * {@link RuntimeException}; wraps any other {@code Throwable} once in an {@link UndeclaredThrowableException}.
     */
    static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new UndeclaredThrowableException(failure);
    }
}
