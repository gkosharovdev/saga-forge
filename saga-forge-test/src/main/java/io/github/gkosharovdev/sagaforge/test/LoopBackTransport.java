package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The fixture's transport (research §4): an {@code IN_TRANSACTION} outbox that releases a batch
 * only once its unit of work committed, and a dispatcher; what they hand off waits in one queue
 * that {@link #drain} delivers on the test thread, in order, events to the runtime and outbox
 * commands to its {@code CommandReceiver}, until nothing is left. Delivery is synchronous; no lease
 * or ordering across aggregates is simulated.
 *
 * <p>A failing outbox command is classified as the in-memory transport's relay classifies it
 * ({@code specs/012-outbox-command-failure-feedback} contracts/fixture.md): a {@code FinalFailure}
 * is given up on after one attempt, any other failure is delivered again, at once, up to {@code
 * outboxCommandAttempts} attempts. A command a saga sent that was given up on is reported to {@code
 * CommandReceiver.receiveFailure} as {@code FINAL} or {@code EXHAUSTED}, so its saga reacts in the
 * same drain. Every failed attempt is handed to {@code commandFailures}.
 *
 * <p>Outcome events ({@code specs/011-saga-outcome-events}) of committed batches are collected for
 * {@link EventResult#outcomeEvents()} instead of being queued: the runtime hands them to no saga.
 */
final class LoopBackTransport implements EventDispatcher, Outbox {

  /** Most deliveries one drain makes before it assumes sagas are feeding each other forever. */
  static final int MAX_DELIVERIES = 10_000;

  private final int outboxCommandAttempts;
  private final Deque<Object> queue = new ArrayDeque<>();
  private final List<OutgoingMessages> staged = new ArrayList<>();
  private final List<SagaOutcome> outcomes = new ArrayList<>();
  private final ScheduledMessages scheduled;

  /**
   * @param outboxCommandAttempts attempts of a failing outbox command before it is reported; at
   *     least 1
   * @param scheduled where deadlines and scheduled commands wait until due (017)
   */
  LoopBackTransport(int outboxCommandAttempts, ScheduledMessages scheduled) {
    this.outboxCommandAttempts = outboxCommandAttempts;
    this.scheduled = scheduled;
  }

  @Override
  public synchronized void dispatch(Object domainEvent) {
    if (domainEvent == null) {
      throw new IllegalArgumentException("domainEvent must not be null");
    }
    queue.add(domainEvent);
  }

  @Override
  public void registerSagaType(Class<?> sagaClass) {
    if (sagaClass == null) {
      throw new IllegalArgumentException("sagaClass must not be null");
    }
  }

  @Override
  public Timing timing() {
    return Timing.IN_TRANSACTION;
  }

  @Override
  public boolean supportsCommands() {
    return true;
  }

  @Override
  public boolean supportsScheduling() {
    return true;
  }

  @Override
  public synchronized void store(OutgoingMessages batch) {
    staged.add(batch);
    scheduled.add(batch.scheduled());
  }

  @Override
  public Set<String> cancel(Collection<String> messageIds) {
    return scheduled.cancel(messageIds);
  }

  @Override
  public synchronized void committed(OutgoingMessages batch) {
    if (staged.remove(batch)) {
      for (Object event : batch.events()) {
        if (event instanceof SagaOutcome outcome) {
          outcomes.add(outcome);
        } else if (batch.sagaId() != null) {
          queue.add(new SourcedEvent(event, batch.sagaId()));
        } else {
          queue.add(event);
        }
      }
      queue.addAll(batch.commands());
    }
  }

  /**
   * @return the outcome events committed since the last call, in commit order
   */
  synchronized List<SagaOutcome> takeOutcomes() {
    List<SagaOutcome> taken = List.copyOf(outcomes);
    outcomes.clear();
    return taken;
  }

  /**
   * Delivers everything queued, including what the deliveries queue in turn.
   *
   * @param reports receives the report of every delivered event
   * @param commandFailures receives the exception of every failed attempt of an outbox command
   */
  void drain(
      SagaRuntime runtime,
      FixtureCommandInterceptor interceptor,
      Consumer<HandlingReport> reports,
      Consumer<RuntimeException> commandFailures) {
    int deliveries = 0;
    Object next;
    while ((next = poll()) != null) {
      if (++deliveries > MAX_DELIVERIES) {
        throw new IllegalStateException(
            "more than "
                + MAX_DELIVERIES
                + " messages were delivered for one"
                + " event; do two sagas keep triggering each other?");
      }
      if (next instanceof OutgoingCommand command) {
        deliverCommand(runtime, interceptor, command, commandFailures);
      } else if (next instanceof SourcedEvent sourced) {
        // 014: delivered with the instance that recorded it, as the in-memory transport does.
        reports.accept(runtime.receive(sourced.event(), sourced.sourceSagaId()));
      } else {
        reports.accept(runtime.receive(next));
      }
    }
    synchronized (this) {
      staged.clear();
    }
  }

  /**
   * Delivers every scheduled message due at {@code now}, earliest first, each followed by a {@link
   * #drain}, until none is due ({@code specs/017-saga-deadlines} contracts/fixture.md): deadlines
   * to the runtime, scheduled commands to its {@code CommandReceiver}.
   */
  void deliverDue(
      SagaRuntime runtime,
      FixtureCommandInterceptor interceptor,
      Supplier<Instant> now,
      Consumer<HandlingReport> reports,
      Consumer<RuntimeException> commandFailures) {
    drain(runtime, interceptor, reports, commandFailures);
    int deliveries = 0;
    Optional<ScheduledMessage> due;
    while ((due = scheduled.takeDue(now.get())).isPresent()) {
      if (++deliveries > MAX_DELIVERIES) {
        throw new IllegalStateException(
            "more than "
                + MAX_DELIVERIES
                + " scheduled messages came due at "
                + now.get()
                + "; does a timeout step keep re-arming its deadline with no duration?");
      }
      ScheduledMessage message = due.get();
      if (message.kind() == MessageKind.COMMAND) {
        deliverCommand(
            runtime,
            interceptor,
            new OutgoingCommand(message.commandId(), message.payload()),
            commandFailures);
      } else {
        reports.accept(runtime.receive(message.payload()));
      }
      drain(runtime, interceptor, reports, commandFailures);
    }
  }

  /**
   * Delivers {@code command} until it is handled or given up on; reports a given-up saga-scoped
   * command.
   */
  private void deliverCommand(
      SagaRuntime runtime,
      FixtureCommandInterceptor interceptor,
      OutgoingCommand command,
      Consumer<RuntimeException> commandFailures) {
    for (int attempt = 1; ; attempt++) {
      RuntimeException failure = attempt(runtime, interceptor, command);
      if (failure == null) {
        return;
      }
      commandFailures.accept(failure);
      boolean isFinal = FailureClassification.of(failure) == FailureClassification.FINAL;
      if (isFinal || attempt >= outboxCommandAttempts) {
        if (command.commandId().sagaScoped()) {
          runtime
              .commandReceiver()
              .receiveFailure(
                  new CommandFailure(
                      command.commandId(),
                      isFinal ? FailureClassification.FINAL : FailureClassification.EXHAUSTED,
                      failure.getClass().getName(),
                      attempt),
                  transaction -> {});
        }
        return;
      }
    }
  }

  /**
   * @return the failure of one delivery of {@code command}, or {@code null} if it was handled
   */
  private static RuntimeException attempt(
      SagaRuntime runtime, FixtureCommandInterceptor interceptor, OutgoingCommand command) {
    interceptor.deliveringOutbox(true);
    try {
      runtime
          .commandReceiver()
          .receive(new OutboxCommand(command.commandId(), command.command(), transaction -> {}));
      return null;
    } catch (RuntimeException failure) {
      return failure;
    } finally {
      interceptor.deliveringOutbox(false);
    }
  }

  /** An event recorded in a unit of work, queued with the instance that recorded it (014). */
  private record SourcedEvent(Object event, String sourceSagaId) {}

  private synchronized Object poll() {
    return queue.poll();
  }
}
