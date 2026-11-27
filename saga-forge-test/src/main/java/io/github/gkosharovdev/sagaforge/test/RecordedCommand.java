package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;

/**
 * One handling of a command the runtime performed during a fixture run ({@code
 * specs/009-saga-test-fixture} data-model.md).
 *
 * @param command the command object
 * @param commandId its identity; {@link CommandId#stepName()} is the step or compensation that sent
 *     it
 * @param delivery whether it ran in the sending unit of work or was delivered through the outbox
 *     after it committed
 * @param attempt 1 for its first handling, 2 for the retry of the same identity, and so on
 * @param handled {@code true} if a registered handler ran; {@code false} if it was only recorded,
 *     for the test to answer (record-and-answer, FR-011), or failed by a failure rule
 * @param metadata the process metadata its handler sees as a {@code SagaMetadata} parameter ({@code
 *     specs/019-process-metadata}); never {@code null}
 */
public record RecordedCommand(
    Object command,
    CommandId commandId,
    Delivery delivery,
    int attempt,
    boolean handled,
    SagaMetadata metadata) {

  /** A {@code null} metadata becomes empty metadata. */
  public RecordedCommand {
    metadata = metadata == null ? SagaMetadata.empty() : metadata;
  }

  /** A recorded command with empty metadata, as before {@code specs/019-process-metadata}. */
  public RecordedCommand(
      Object command, CommandId commandId, Delivery delivery, int attempt, boolean handled) {
    this(command, commandId, delivery, attempt, handled, SagaMetadata.empty());
  }

  /** How the command reached its handling. */
  public enum Delivery {

    /** Handled inside the sending unit of work. */
    IN_UNIT_OF_WORK,

    /** {@code @Command(delivery = OUTBOX)}: handled after the sending unit of work committed. */
    OUTBOX
  }

  /** The step or compensation that sent it. */
  public String step() {
    return commandId.stepName();
  }

  @Override
  public String toString() {
    return String.valueOf(command);
  }
}
