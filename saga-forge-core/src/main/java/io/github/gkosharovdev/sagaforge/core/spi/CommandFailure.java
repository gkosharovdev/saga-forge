package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;

/**
 * A transport's report that a saga-scoped outbox command failed for good, handed to {@link
 * CommandReceiver#receiveFailure} ({@code specs/012-outbox-command-failure-feedback} FR-004,
 * FR-006).
 *
 * <p>The report carries no failure message: what the handler said stays with the transport's
 * set-aside record, never in saga state or outcome events. Two reports of the same failed delivery
 * are equal.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code commandId} is
 * {@code null} or not saga-scoped, {@code classification} is not {@code FINAL} or {@code
 * EXHAUSTED}, {@code causeType} is blank or {@code attempts < 1}.
 *
 * @param commandId the identity assigned when the saga sent the command; names the instance and the
 *     sender
 * @param classification {@code FINAL} (the handler marked the failure final) or {@code EXHAUSTED}
 *     (the transport's attempts were used up)
 * @param causeType fully qualified class name of the last failure
 * @param attempts handler attempts made, including the last; at least 1
 */
public record CommandFailure(
    CommandId commandId, FailureClassification classification, String causeType, int attempts) {

  public CommandFailure {
    if (commandId == null) {
      throw new IllegalArgumentException("commandId must not be null");
    }
    if (!commandId.sagaScoped()) {
      throw new IllegalArgumentException(
          "commandId must be saga-scoped, but " + commandId + " is not");
    }
    if (classification != FailureClassification.FINAL
        && classification != FailureClassification.EXHAUSTED) {
      throw new IllegalArgumentException(
          "classification must be FINAL or EXHAUSTED, but is " + classification);
    }
    if (causeType == null || causeType.isBlank()) {
      throw new IllegalArgumentException("causeType must not be blank");
    }
    if (attempts < 1) {
      throw new IllegalArgumentException("attempts must be at least 1, but is " + attempts);
    }
  }

  /**
   * @return the instance that sent the command
   */
  public String sagaId() {
    return commandId.sagaId();
  }

  /**
   * @return the step or compensation method that sent the command
   */
  public String senderName() {
    return commandId.stepName();
  }

  /**
   * @return fully qualified class name of the command
   */
  public String commandType() {
    return commandId.commandType();
  }
}
