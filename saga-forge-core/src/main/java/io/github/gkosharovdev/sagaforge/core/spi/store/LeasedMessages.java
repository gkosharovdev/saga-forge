package io.github.gkosharovdev.sagaforge.core.spi.store;

import java.util.List;

/**
 * The result of one {@link MessageStore#lease}: every message in it is now leased to the caller, whether its payload
 * could be decoded ({@code readable}) or not ({@code unreadable}, to be set aside).
 *
 * @param readable   leased messages with their payload, in position order; non-null
 * @param unreadable leased messages whose payload could not be decoded; non-null
 */
public record LeasedMessages(List<StoredMessage> readable, List<UnreadableMessageException> unreadable) {

    public LeasedMessages {
        Checks.nonNull(readable, "readable");
        Checks.nonNull(unreadable, "unreadable");
        readable = List.copyOf(readable);
        unreadable = List.copyOf(unreadable);
    }

    /** @return whether nothing was leased */
    public boolean isEmpty() {
        return readable.isEmpty() && unreadable.isEmpty();
    }

    /** @return the number of leased messages */
    public int size() {
        return readable.size() + unreadable.size();
    }
}
