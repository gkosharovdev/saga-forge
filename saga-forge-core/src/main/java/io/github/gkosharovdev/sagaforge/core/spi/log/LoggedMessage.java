package io.github.gkosharovdev.sagaforge.core.spi.log;

import java.time.Instant;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * A message as stored in the {@link MessageLog}, returned by leases and queries
 * ({@code specs/007-in-memory-transport} data-model.md).
 *
 * @param position       assigned at append, strictly increasing per log
 * @param messageId      see {@link LogEntry#messageId()}; non-blank
 * @param kind           non-null
 * @param payloadType    non-blank
 * @param payload        the decoded payload, or {@code null} when not loaded (a query without payload, an
 *                       unreadable entry, or an interop delivery record before its publication is resolved)
 * @param commandId      non-null if and only if {@code kind} is {@link MessageKind#COMMAND}
 * @param orderingKey    may be {@code null}
 * @param sourceSagaType both or neither with {@code sourceSagaId}
 * @param sourceSagaId   see {@code sourceSagaType}
 * @param recordedAt     non-null
 * @param status         non-null
 * @param attempts       delivery attempts so far, counted at lease; not negative
 * @param nextAttemptAt  not leasable before this time; non-null
 * @param lastFailure    the latest failure; may be {@code null}
 * @param leasedBy      the leaseholder; both or neither with {@code leasedUntil}
 * @param leasedUntil   when the lease expires
 * @param consumedAt     non-null if and only if {@code status} is {@link MessageStatus#CONSUMED}
 * @param setAsideReason non-null if and only if {@code status} is {@link MessageStatus#SET_ASIDE}
 * @param externalRef    in Spring Modulith interop mode the registry's publication identity; otherwise {@code null}
 * @throws IllegalArgumentException if a rule above is violated
 */
public record LoggedMessage(long position, String messageId, MessageKind kind, String payloadType, Object payload,
        CommandId commandId, String orderingKey, String sourceSagaType, String sourceSagaId, Instant recordedAt,
        MessageStatus status, int attempts, Instant nextAttemptAt, DeliveryFailure lastFailure, String leasedBy,
        Instant leasedUntil, Instant consumedAt, String setAsideReason, String externalRef) {

    public LoggedMessage {
        Checks.nonBlank(messageId, "messageId");
        Checks.nonNull(kind, "kind");
        Checks.nonBlank(payloadType, "payloadType");
        Checks.nonNull(recordedAt, "recordedAt");
        Checks.nonNull(status, "status");
        Checks.nonNull(nextAttemptAt, "nextAttemptAt");
        Checks.commandIdMatchesKind(kind, commandId);
        Checks.bothOrNeither(sourceSagaType, sourceSagaId, "sourceSagaType", "sourceSagaId");
        Checks.bothOrNeither(leasedBy, leasedUntil, "leasedBy", "leasedUntil");
        Checks.presentIff(status == MessageStatus.CONSUMED, consumedAt, "consumedAt", "status is CONSUMED");
        Checks.presentIff(status == MessageStatus.SET_ASIDE, setAsideReason, "setAsideReason",
                "status is SET_ASIDE");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative, but is " + attempts);
        }
    }

    /**
     * A freshly appended, unconsumed message for {@code entry}.
     *
     * @param position the assigned position
     * @param entry    the appended entry; non-null
     * @return the message with status {@link MessageStatus#UNCONSUMED}, no attempts, due at its recording time
     */
    public static LoggedMessage appended(long position, LogEntry entry) {
        return new LoggedMessage(position, entry.messageId(), entry.kind(), entry.payloadType(), entry.payload(),
                entry.commandId(), entry.orderingKey(), entry.sourceSagaType(), entry.sourceSagaId(),
                entry.recordedAt(), MessageStatus.UNCONSUMED, 0, entry.recordedAt(), null, null, null, null, null,
                null);
    }

    /** @return whether the payload is loaded */
    public boolean hasPayload() {
        return payload != null;
    }

    /** @return this message without its payload */
    public LoggedMessage withoutPayload() {
        return withPayload(null);
    }

    /**
     * @param newPayload the payload to carry, or {@code null}
     * @return this message carrying {@code newPayload}
     */
    public LoggedMessage withPayload(Object newPayload) {
        return new LoggedMessage(position, messageId, kind, payloadType, newPayload, commandId, orderingKey,
                sourceSagaType, sourceSagaId, recordedAt, status, attempts, nextAttemptAt, lastFailure, leasedBy,
                leasedUntil, consumedAt, setAsideReason, externalRef);
    }
}
