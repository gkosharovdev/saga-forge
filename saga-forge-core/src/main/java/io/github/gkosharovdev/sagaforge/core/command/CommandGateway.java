package io.github.gkosharovdev.sagaforge.core.command;

/**
 * Dispatches a {@link io.github.gkosharovdev.sagaforge.core.annotation.Command @Command}-annotated
 * object to the single {@link
 * io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler @CommandHandler} for its type.
 *
 * <p>{@code @SagaStep} methods reach it through one optional {@code CommandGateway}-typed field on
 * the saga (at most one per saga); services and application code obtain it through dependency
 * injection. Only the contract lives in {@code saga-forge-core}; the engine runtime provides the
 * implementation, the same posture as {@code StorageProvider} and {@code EventDispatcher}.
 *
 * <h2>Handler kinds</h2>
 *
 * <ul>
 *   <li><b>Service-hosted handler</b> (a {@code @CommandHandler} method on a non-{@code @Saga},
 *       non-{@code @Aggregate} class): the target is obtained from the host's {@code
 *       HandlerInstanceProvider.instanceOf(handlerClass)}.
 *   <li><b>Creation handler</b> (a constructor or static factory of an {@link
 *       io.github.gkosharovdev.sagaforge.core.annotation.Aggregate @Aggregate}): invoked directly;
 *       the new aggregate is then saved.
 *   <li><b>Instance-method handler</b> of an {@code @Aggregate}: the target id is the command's
 *       {@link
 *       io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier @AggregateIdentifier}
 *       value. In order, the gateway:
 *       <ol>
 *         <li>loads the aggregate with the client's {@link
 *             io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader @AggregateLoader}
 *             method for that type if one exists, otherwise with the host's {@code
 *             AggregateResolver};
 *         <li>invokes the handler on the loaded aggregate;
 *         <li>saves the aggregate.
 *       </ol>
 * </ul>
 *
 * <p><b>Saving</b> uses the client's {@link
 * io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver @AggregateSaver} method for that
 * type if one exists, otherwise {@code AggregateResolver.save}, otherwise nothing (the client's own
 * unit of work persists the change, for example through JPA dirty checking). Client code never
 * implements a Saga Forge interface.
 *
 * <p>The handler's return value, if any, is ignored. Domain events recorded during the handler
 * through {@code DomainEvents.record} go to the current unit of work's recorder.
 *
 * <h2>Outside a unit of work</h2>
 *
 * <p>When {@code send} is called with no active unit of work (for example from a request handler),
 * the gateway opens a unit of work for the handler's own changes. That unit of work is not
 * saga-scoped: events raised in it are delivered after it commits through the after-commit outbox
 * ({@link io.github.gkosharovdev.sagaforge.core.spi.Outbox}; by default {@link
 * io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher#dispatch(Object)}).
 */
public interface CommandGateway {

  /**
   * Runs the single {@code @CommandHandler} whose {@code @Command}-typed parameter matches the
   * class of {@code command}, synchronously and to completion, inside the caller's current
   * saga-scoped unit of work.
   *
   * <p>Returns only after the handler returned and, for aggregate handlers, the aggregate was
   * saved.
   *
   * <p>Any exception thrown by the handler, an {@code @AggregateLoader} or {@code @AggregateSaver},
   * the {@code AggregateResolver}, or the {@code HandlerInstanceProvider} propagates unchanged. The
   * unit of work is marked for rollback before the exception leaves {@code send}, so a step that
   * catches the exception cannot prevent the rollback.
   *
   * @param command the command to dispatch; non-null, its class annotated {@code @Command}
   * @throws IllegalArgumentException if {@code command} is {@code null} or its class is not
   *     annotated {@code @Command}
   * @throws NoCommandHandlerException if no {@code @CommandHandler} exists for the command's type;
   *     the unit of work is rolled back
   * @throws AggregateNotFoundException if the target aggregate of an instance-method handler is not
   *     found by the loader or resolver
   */
  void send(Object command);

  /**
   * Stores {@code command} to be handled at {@code at} or soon after ({@code
   * specs/017-saga-deadlines} FR-007). The command is stored with the caller's unit of work, so it
   * is scheduled only if that unit of work commits; with no active unit of work, a unit of work of
   * its own is opened, as for {@link #send(Object)}. When due, it is handled like a
   * {@code @Command(delivery = OUTBOX)} command sent at scheduling time, with the {@link CommandId}
   * assigned now: from a saga step, a final failure of it is reported to the saga.
   *
   * <p>Requires an outbox that can schedule messages; the default implementation throws {@link
   * UnsupportedOperationException}.
   *
   * @param command the command; non-null, its class annotated {@code @Command(delivery = OUTBOX)}
   * @param at when it becomes due; a time in the past is due at once
   * @return the handle to {@linkplain #cancel(ScheduledCommandId) cancel} it with
   * @throws IllegalArgumentException if {@code command} or {@code at} is {@code null}, or the
   *     command's type is not an outbox command
   * @throws UnsupportedOperationException if the outbox cannot schedule messages
   */
  default ScheduledCommandId schedule(Object command, java.time.Instant at) {
    throw new UnsupportedOperationException("this CommandGateway cannot schedule commands");
  }

  /**
   * Withdraws a scheduled command that has not been handed over yet, in the caller's unit of work
   * (or one of its own). A command scheduled earlier in the same unit of work is simply never
   * stored.
   *
   * @param id the handle {@link #schedule(Object, java.time.Instant)} returned; non-null
   * @return whether a pending command was withdrawn; {@code false} if it is being delivered, was
   *     handled, was already cancelled, or is unknown
   * @throws IllegalArgumentException if {@code id} is {@code null}
   * @throws UnsupportedOperationException if the outbox cannot schedule messages
   */
  default boolean cancel(ScheduledCommandId id) {
    throw new UnsupportedOperationException("this CommandGateway cannot cancel scheduled commands");
  }
}
