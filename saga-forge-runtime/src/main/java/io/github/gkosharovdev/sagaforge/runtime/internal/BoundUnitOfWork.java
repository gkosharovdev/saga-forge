package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import java.util.Optional;

/**
 * What the command gateway and the reentrancy guard see of the engine {@link UnitOfWork} bound to
 * their thread through {@link DomainEventScope#runWith(DomainEventRecorder, Runnable)}: a recorder
 * of domain events that also accepts failure reports, command identities and outbox commands.
 *
 * <p>The gateway reports every handler, loader, saver, resolver or provider failure before the
 * exception leaves {@code send}, so the unit of work fails even if the step or handler that called
 * {@code send} catches the exception (002 FR-012).
 */
interface BoundUnitOfWork extends DomainEventRecorder {

  /** Reports a failure inside this unit of work; the first report is kept and fails it. */
  void failed(Throwable failure);

  /** The metadata of this unit of work ({@code specs/019-process-metadata}). */
  default SagaMetadata metadata() {
    return SagaMetadata.empty();
  }

  /**
   * The identity of the next command of {@code commandType} sent in this unit of work (005 FR-001).
   * The default, for units of work outside a saga, is a fresh {@linkplain
   * CommandId#unscoped(String) unscoped} identity.
   */
  default CommandId nextCommandId(Class<?> commandType) {
    return CommandId.unscoped(commandType.getName());
  }

  /**
   * Whether handling of {@code id} already committed for this unit of work's instance (005 FR-004);
   * its handler must then not be invoked again. Always {@code false} outside a saga.
   */
  default boolean alreadyHandled(CommandId id) {
    return false;
  }

  /**
   * Records that {@code id}'s handler returned in this unit of work; it commits with the running
   * work.
   */
  default void commandHandled(CommandId id) {}

  /**
   * Buffers a {@code @Command(delivery = OUTBOX)} command for this unit of work's outbox batch
   * ({@code specs/007-in-memory-transport} research §3); it is discarded if the unit of work rolls
   * back.
   */
  void recordOutboxCommand(OutgoingCommand command);

  /**
   * The id of the next command scheduled in this unit of work: {@code <unitOfWorkId>:scheduled:<n>}
   * (017).
   */
  String nextScheduledMessageId();

  /** Stores {@code message} with this unit of work's batch (017). */
  void recordScheduled(ScheduledMessage message);

  /**
   * Withdraws the scheduled message {@code messageId}: dropped from this unit of work's batch if it
   * was scheduled here, else cancelled through the outbox in this unit of work's transaction (017).
   *
   * @return whether a pending message was withdrawn
   */
  boolean cancelScheduled(String messageId);

  /** The engine unit of work bound to the calling thread, if any; another recorder is not one. */
  static Optional<BoundUnitOfWork> current() {
    return DomainEventScope.current()
        .filter(BoundUnitOfWork.class::isInstance)
        .map(BoundUnitOfWork.class::cast);
  }
}
