package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.function.Consumer;

/**
 * One delivery of a outbox command to {@link CommandReceiver#receive(OutboxCommand)}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code
 * null}.
 *
 * @param commandId the identity assigned when the command was sent; bound for a {@code CommandId}
 *     handler parameter; non-null
 * @param command the command; non-null
 * @param inTransaction called once after the handler returned and before the handling unit of work
 *     commits, with that unit of work's transaction, so a transport can mark the message consumed
 *     atomically with the handler's effects; non-null
 * @param metadata the process metadata of the command's message ({@code
 *     specs/019-process-metadata}): the handling unit of work runs with it; {@code null} becomes
 *     empty
 */
public record OutboxCommand(
    CommandId commandId,
    Object command,
    Consumer<LocalTransaction> inTransaction,
    SagaMetadata metadata) {

  /** A delivery without metadata, as before {@code specs/019-process-metadata}. */
  public OutboxCommand(
      CommandId commandId, Object command, Consumer<LocalTransaction> inTransaction) {
    this(commandId, command, inTransaction, SagaMetadata.empty());
  }

  public OutboxCommand {
    if (commandId == null) {
      throw new IllegalArgumentException("commandId must not be null");
    }
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }
    if (inTransaction == null) {
      throw new IllegalArgumentException("inTransaction must not be null");
    }
    metadata = metadata == null ? SagaMetadata.empty() : metadata;
  }
}
