package io.github.gkosharovdev.sagaforge.core.spi.store;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import java.time.Instant;
import java.util.Map;

/**
 * One message to append to the {@link MessageStore}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a rule above is violated.
 *
 * @param messageId unique in the store: {@code unitOfWorkId + ":" + index} for an event of a unit
 *     of work, the {@code CommandId} value for a outbox command, a random UUID for an event
 *     published outside any unit of work; non-blank
 * @param kind event or command; non-null
 * @param payloadType fully qualified class name of the payload; non-blank
 * @param payload the event or command; non-null
 * @param commandId the command's identity; non-null if and only if {@code kind} is {@link
 *     MessageKind#COMMAND}
 * @param orderingKey messages with the same key are delivered one at a time in position order;
 *     {@code null} for no ordering
 * @param sourceSagaType the saga type of the unit of work that produced it; non-null if and only if
 *     {@code sourceSagaId} is
 * @param sourceSagaId the saga instance of the unit of work that produced it
 * @param recordedAt when it was appended; non-null
 * @param traceContext the producing unit of work's trace context; {@code null} becomes empty
 * @param notBefore not leasable before this time: the message's first {@code nextAttemptAt}; {@code
 *     null} for due at once. A message with a {@code notBefore} has no ordering key, so it never
 *     holds back other messages ({@code specs/017-saga-deadlines} FR-001, research R1, R2)
 */
public record NewMessage(
    String messageId,
    MessageKind kind,
    String payloadType,
    Object payload,
    CommandId commandId,
    String orderingKey,
    String sourceSagaType,
    String sourceSagaId,
    Instant recordedAt,
    Map<String, String> traceContext,
    Instant notBefore) {

  /**
   * Validates the components; a {@code null} {@code traceContext} becomes empty, and it is stored
   * as an unmodifiable copy ({@code specs/010-otel-observability} contracts/message-context.md).
   */
  public NewMessage {
    Checks.nonBlank(messageId, "messageId");
    Checks.nonNull(kind, "kind");
    Checks.nonBlank(payloadType, "payloadType");
    Checks.nonNull(payload, "payload");
    Checks.nonNull(recordedAt, "recordedAt");
    Checks.commandIdMatchesKind(kind, commandId);
    Checks.bothOrNeither(sourceSagaType, sourceSagaId, "sourceSagaType", "sourceSagaId");
    traceContext = traceContext == null ? Map.of() : Map.copyOf(traceContext);
    if (notBefore != null && orderingKey != null) {
      throw new IllegalArgumentException(
          "a message with notBefore must not have an orderingKey, but has " + orderingKey);
    }
  }

  /** A message due at once (the 010 shape). */
  public NewMessage(
      String messageId,
      MessageKind kind,
      String payloadType,
      Object payload,
      CommandId commandId,
      String orderingKey,
      String sourceSagaType,
      String sourceSagaId,
      Instant recordedAt,
      Map<String, String> traceContext) {
    this(
        messageId,
        kind,
        payloadType,
        payload,
        commandId,
        orderingKey,
        sourceSagaType,
        sourceSagaId,
        recordedAt,
        traceContext,
        null);
  }

  /**
   * @return whether the message waits for {@link #notBefore()}
   */
  public boolean isScheduled() {
    return notBefore != null;
  }

  /** A message without trace context (the 007 shape). */
  public NewMessage(
      String messageId,
      MessageKind kind,
      String payloadType,
      Object payload,
      CommandId commandId,
      String orderingKey,
      String sourceSagaType,
      String sourceSagaId,
      Instant recordedAt) {
    this(
        messageId,
        kind,
        payloadType,
        payload,
        commandId,
        orderingKey,
        sourceSagaType,
        sourceSagaId,
        recordedAt,
        Map.of(),
        null);
  }
}
