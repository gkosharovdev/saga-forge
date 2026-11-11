package io.github.gkosharovdev.sagaforge.core.command;

/**
 * Dispatches a {@link io.github.gkosharovdev.sagaforge.core.annotation.Command @Command}-annotated object
 * to the single {@link io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler @CommandHandler}
 * for its type.
 *
 * <p>{@code @SagaStep} methods reach it through one optional {@code CommandGateway}-typed field on the saga
 * (at most one per saga); services and application code obtain it through dependency injection. Only the
 * contract lives in {@code saga-forge-core}; the engine runtime provides the implementation, the same
 * posture as {@code StorageProvider} and {@code EventDispatcher}.
 *
 * <h2>Handler kinds</h2>
 *
 * <ul>
 *   <li><b>Service-hosted handler</b> (a {@code @CommandHandler} method on a non-{@code @Saga},
 *       non-{@code @Aggregate} class): the target is obtained from the host's
 *       {@code HandlerInstanceProvider.instanceOf(handlerClass)}.
 *   <li><b>Creation handler</b> (a constructor or static factory of an
 *       {@link io.github.gkosharovdev.sagaforge.core.annotation.Aggregate @Aggregate}): invoked directly;
 *       the new aggregate is then saved.
 *   <li><b>Instance-method handler</b> of an {@code @Aggregate}: the target id is the command's
 *       {@link io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier @AggregateIdentifier}
 *       value. In order, the gateway:
 *       <ol>
 *         <li>loads the aggregate with the client's
 *             {@link io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader @AggregateLoader}
 *             method for that type if one exists, otherwise with the host's {@code AggregateResolver};
 *         <li>invokes the handler on the loaded aggregate;
 *         <li>saves the aggregate.
 *       </ol>
 * </ul>
 *
 * <p><b>Saving</b> uses the client's
 * {@link io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver @AggregateSaver} method for that
 * type if one exists, otherwise {@code AggregateResolver.save}, otherwise nothing (the client's own unit of
 * work persists the change, for example through JPA dirty checking). Client code never implements a Saga
 * Forge interface.
 *
 * <p>The handler's return value, if any, is ignored. Domain events recorded during the handler through
 * {@code DomainEvents.record} go to the current unit of work's recorder.
 *
 * <h2>Outside a unit of work</h2>
 *
 * <p>When {@code send} is called with no active unit of work (for example from a request handler), the
 * gateway opens a unit of work for the handler's own changes. That unit of work is not saga-scoped: events
 * raised in it are delivered after it commits through the after-commit outbox
 * ({@link io.github.gkosharovdev.sagaforge.core.spi.Outbox}; by default
 * {@link io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher#dispatch(Object)}).
 */
public interface CommandGateway {

    /**
     * Runs the single {@code @CommandHandler} whose {@code @Command}-typed parameter matches the class of
     * {@code command}, synchronously and to completion, inside the caller's current saga-scoped unit of
     * work.
     *
     * <p>Returns only after the handler returned and, for aggregate handlers, the aggregate was saved.
     *
     * <p>Any exception thrown by the handler, an {@code @AggregateLoader} or {@code @AggregateSaver}, the
     * {@code AggregateResolver}, or the {@code HandlerInstanceProvider} propagates unchanged. The unit of
     * work is marked for rollback before the exception leaves {@code send}, so a step that catches the
     * exception cannot prevent the rollback.
     *
     * @param command the command to dispatch; non-null, its class annotated {@code @Command}
     * @throws IllegalArgumentException   if {@code command} is {@code null} or its class is not annotated
     *                                    {@code @Command}
     * @throws NoCommandHandlerException  if no {@code @CommandHandler} exists for the command's type; the
     *                                    unit of work is rolled back
     * @throws AggregateNotFoundException if the target aggregate of an instance-method handler is not found
     *                                    by the loader or resolver
     */
    void send(Object command);
}
