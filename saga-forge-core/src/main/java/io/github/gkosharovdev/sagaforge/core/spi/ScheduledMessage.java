package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import java.time.Instant;

/**
 * A message a unit of work stores to be handed over at {@code notBefore} or later, one element of
 * {@link OutgoingMessages#scheduled()} ({@code specs/017-saga-deadlines}): a deadline the unit of work started
 * ({@link MessageKind#EVENT}, payload {@link DeadlineReached}) or a command it scheduled ({@link MessageKind#COMMAND}).
 * An outbox stores it with no ordering key.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code null} (other than
 * {@code commandId} for an event), {@code messageId} is blank, {@code commandId} is present for an event or absent for a
 * command, or an event's payload is not a {@link DeadlineReached}.</p>
 *
 * @param messageId the stored message's id, also the handle used to cancel it
 * @param kind      event (a deadline) or command
 * @param payload   the {@link DeadlineReached} or the command
 * @param commandId the command's identity, assigned when it was scheduled; non-null if and only if {@code kind} is
 *                  {@link MessageKind#COMMAND}
 * @param notBefore when it becomes due
 */
public record ScheduledMessage(String messageId, MessageKind kind, Object payload, CommandId commandId,
        Instant notBefore) {

    /** Validates every component. */
    public ScheduledMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (kind == null || payload == null || notBefore == null) {
            throw new IllegalArgumentException("kind, payload and notBefore must not be null");
        }
        if ((kind == MessageKind.COMMAND) != (commandId != null)) {
            throw new IllegalArgumentException("commandId must be non-null if and only if kind is COMMAND, but kind is "
                    + kind);
        }
        if (kind == MessageKind.EVENT && !(payload instanceof DeadlineReached)) {
            throw new IllegalArgumentException("a scheduled event must be a DeadlineReached, but is "
                    + payload.getClass().getName());
        }
    }

    /**
     * @param deadline the deadline that was started
     * @return its scheduled message, due at {@link DeadlineReached#dueAt()}
     */
    public static ScheduledMessage deadline(DeadlineReached deadline) {
        return new ScheduledMessage(deadline.deadlineId(), MessageKind.EVENT, deadline, null, deadline.dueAt());
    }
}
