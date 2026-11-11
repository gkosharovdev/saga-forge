package io.github.gkosharovdev.sagaforge.core.spi.log;

import java.time.Instant;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * One message to append to the {@link MessageLog}.
 *
 * @param messageId      unique in the log: {@code unitOfWorkId + ":" + index} for an event of a unit of work, the
 *                       {@code CommandId} value for a logged command, a random UUID for an event published outside
 *                       any unit of work; non-blank
 * @param kind           event or command; non-null
 * @param payloadType    fully qualified class name of the payload; non-blank
 * @param payload        the event or command; non-null
 * @param commandId      the command's identity; non-null if and only if {@code kind} is {@link MessageKind#COMMAND}
 * @param orderingKey    messages with the same key are delivered one at a time in position order; {@code null} for
 *                       no ordering
 * @param sourceSagaType the saga type of the unit of work that produced it; non-null if and only if
 *                       {@code sourceSagaId} is
 * @param sourceSagaId   the saga instance of the unit of work that produced it
 * @param recordedAt     when it was appended; non-null
 * @throws IllegalArgumentException if a rule above is violated
 */
public record LogEntry(String messageId, MessageKind kind, String payloadType, Object payload, CommandId commandId,
        String orderingKey, String sourceSagaType, String sourceSagaId, Instant recordedAt) {

    public LogEntry {
        Checks.nonBlank(messageId, "messageId");
        Checks.nonNull(kind, "kind");
        Checks.nonBlank(payloadType, "payloadType");
        Checks.nonNull(payload, "payload");
        Checks.nonNull(recordedAt, "recordedAt");
        Checks.commandIdMatchesKind(kind, commandId);
        Checks.bothOrNeither(sourceSagaType, sourceSagaId, "sourceSagaType", "sourceSagaId");
    }
}
