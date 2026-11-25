package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CommandReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link CommandReceiver} standing in for the runtime: each delivery runs {@code handler}, which
 * may throw, and on success consumes the message in a transaction of its own; each failure report
 * is recorded and sets the message aside in a transaction of its own, as the runtime's reaction
 * would (contracts/command-receiver-failure.md C3). {@link #failReports(int)} makes the next
 * reports throw before anything is written (C4). Thread-safe.
 */
public final class RecordingCommandReceiver implements CommandReceiver {

  private final TransactionBoundary transactions;
  private final Consumer<Object> handler;
  private final List<OutboxCommand> deliveries = new CopyOnWriteArrayList<>();
  private final List<CommandFailure> reports = new CopyOnWriteArrayList<>();
  private final AtomicInteger reportFailures = new AtomicInteger();

  public RecordingCommandReceiver(TransactionBoundary transactions, Consumer<Object> handler) {
    this.transactions = transactions;
    this.handler = handler;
  }

  @Override
  public void receive(OutboxCommand delivery) {
    deliveries.add(delivery);
    handler.accept(delivery.command());
    inTransaction(delivery.inTransaction());
  }

  @Override
  public void receiveFailure(CommandFailure failure, Consumer<LocalTransaction> inTransaction) {
    if (reportFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new IllegalStateException("the reaction failed");
    }
    inTransaction(inTransaction);
    reports.add(failure);
  }

  /** Makes the next {@code times} failure reports throw. */
  public void failReports(int times) {
    reportFailures.set(times);
  }

  public List<OutboxCommand> deliveries() {
    return List.copyOf(deliveries);
  }

  public List<CommandFailure> reports() {
    return List.copyOf(reports);
  }

  private void inTransaction(Consumer<LocalTransaction> work) {
    LocalTransaction tx = transactions.begin();
    try {
      work.accept(tx);
      tx.commit();
    } catch (RuntimeException | Error failure) {
      tx.rollback();
      throw failure;
    }
  }
}
