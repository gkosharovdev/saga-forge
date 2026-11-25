package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.function.Consumer;

/**
 * Inbound entry point for outbox commands ({@code @Command(delivery = OUTBOX)}): the counterpart of
 * {@link EventReceiver} for commands a transport delivers from its message store ({@code
 * specs/007-in-memory-transport} research §3).
 *
 * <p><strong>Implemented by the engine</strong> ({@code SagaRuntime.commandReceiver()}); called by
 * transports.
 *
 * <h2>Effect</h2>
 *
 * <p>Synchronously, on the calling thread: opens a new non-saga unit of work, runs the single
 * {@code @CommandHandler} for the command's type with {@link OutboxCommand#commandId()} bound for a
 * {@code CommandId} parameter, then calls {@link OutboxCommand#inTransaction()} with that unit of
 * work's transaction, then commits. Events the handler records go to the {@link Outbox} as for any
 * non-saga unit of work. If anything throws, the unit of work rolls back and the exception
 * propagates; the transport redelivers.
 *
 * <h2>Failures</h2>
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} for a {@code null} delivery or a command whose class is
 *       not {@code @Command}-annotated.
 *   <li>{@link IllegalStateException} if the engine has not started or a unit of work is running on
 *       this thread.
 *   <li>{@code NoCommandHandlerException} if no handler exists for the command's type.
 *   <li>Any {@link RuntimeException} the handler, an aggregate loader or saver, or {@code
 *       inTransaction} threw, after rollback.
 * </ul>
 */
public interface CommandReceiver {

  /**
   * Handles one outbox command in a unit of work of its own.
   *
   * @param delivery the command, its identity and the in-transaction callback; must not be {@code
   *     null}
   */
  void receive(OutboxCommand delivery);

  /**
   * Reports that a saga-scoped outbox command failed for good, so the instance that sent it can
   * react ({@code specs/012-outbox-command-failure-feedback}
   * contracts/command-receiver-failure.md).
   *
   * <p><strong>Caller (transport) obligations.</strong> Call only for a saga-scoped {@code
   * CommandId} whose failure is {@code FINAL} or {@code EXHAUSTED}; set a command sent outside a
   * saga aside yourself. Call at most once per failed delivery, while the message is still leased,
   * from the delivering thread. Set the message aside only inside {@code inTransaction}, with the
   * transaction it is given, never before or after the call. If the call throws, record a failed
   * attempt with the normal backoff and do not set the message aside: the message is redelivered
   * and the report repeated. Carry no failure message.
   *
   * <p><strong>Engine guarantees.</strong> On a normal return {@code inTransaction} was called
   * exactly once, in a transaction that committed together with any change to the instance. Saga
   * state is written only by the engine's unit of work. The instance reacts at most once: later
   * reports for it change nothing. A report for an instance that cannot react (missing, ended,
   * already compensating for a step's command) is logged as a diagnostic naming the saga id, the
   * command type and the reason, and {@code inTransaction} still runs in a transaction of its own.
   * The engine throws only for infrastructure failures, never for an ignored report, and holds the
   * instance for the reaction and its compensations as it does for an event.
   *
   * <p>This default throws {@link UnsupportedOperationException} naming the receiver's class, so a
   * receiver written before the method existed still compiles; a transport calling it treats the
   * exception as a failed attempt.
   *
   * @param failure the report; must not be {@code null}
   * @param inTransaction called once with the reaction's transaction, so the transport can set the
   *     message aside atomically with the reaction; must not be {@code null}
   */
  default void receiveFailure(CommandFailure failure, Consumer<LocalTransaction> inTransaction) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not receive command failures");
  }
}
