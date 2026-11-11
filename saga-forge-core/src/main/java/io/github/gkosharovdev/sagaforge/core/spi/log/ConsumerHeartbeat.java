package io.github.gkosharovdev.sagaforge.core.spi.log;

import java.time.Instant;

/**
 * A relay's sign of life, used to warn about a second running instance ({@code specs/007-in-memory-transport}
 * FR-021).
 *
 * @param consumerId random per transport start; non-blank
 * @param host       host name, informational; non-blank
 * @param startedAt  non-null
 * @param lastSeenAt non-null, not before {@code startedAt}
 * @throws IllegalArgumentException if a rule above is violated
 */
public record ConsumerHeartbeat(String consumerId, String host, Instant startedAt, Instant lastSeenAt) {

    public ConsumerHeartbeat {
        Checks.nonBlank(consumerId, "consumerId");
        Checks.nonBlank(host, "host");
        Checks.nonNull(startedAt, "startedAt");
        Checks.nonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("lastSeenAt must not be before startedAt");
        }
    }
}
