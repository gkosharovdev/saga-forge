package io.github.gkosharovdev.sagaforge.transport.inmemory;

import java.time.Duration;

/**
 * Tuning of an {@link InMemoryTransport} ({@code specs/007-in-memory-transport} contracts/in-memory-transport.md).
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a count is not positive, a duration is not positive, or
 *                                  {@code maxBackoff < initialBackoff}.</p>
 *
 * @param workers                delivery threads; messages with the same ordering key always use the same one
 * @param maxAttempts            failed deliveries before a message is set aside (FR-015)
 * @param initialBackoff         wait after the first failure; doubled per further failure
 * @param maxBackoff             longest wait between attempts
 * @param leaseDuration          how long a leased message is reserved for this transport
 * @param pollInterval           longest wait between polls when nothing signals new messages
 * @param leaseBatchSize         most messages leased at once
 * @param retention              how long consumed messages are kept (FR-019)
 * @param retentionSweepInterval how often consumed messages older than {@code retention} are removed
 * @param heartbeatInterval      how often this transport refreshes its heartbeat (FR-021)
 */
public record TransportSettings(int workers, int maxAttempts, Duration initialBackoff, Duration maxBackoff,
        Duration leaseDuration, Duration pollInterval, int leaseBatchSize, Duration retention,
        Duration retentionSweepInterval, Duration heartbeatInterval) {

    public TransportSettings {
        positive(workers, "workers");
        positive(maxAttempts, "maxAttempts");
        positive(leaseBatchSize, "leaseBatchSize");
        positive(initialBackoff, "initialBackoff");
        positive(maxBackoff, "maxBackoff");
        positive(leaseDuration, "leaseDuration");
        positive(pollInterval, "pollInterval");
        positive(retention, "retention");
        positive(retentionSweepInterval, "retentionSweepInterval");
        positive(heartbeatInterval, "heartbeatInterval");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
    }

    /** @return 4 workers, 10 attempts, backoff 1 s to 1 min, 5 min lease, 500 ms poll, 100 per batch, 7 days kept */
    public static TransportSettings defaults() {
        return new TransportSettings(4, 10, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMillis(500), 100, Duration.ofDays(7), Duration.ofHours(1), Duration.ofSeconds(30));
    }

    /** @return these settings with {@code value} workers */
    public TransportSettings withWorkers(int value) {
        return new TransportSettings(value, maxAttempts, initialBackoff, maxBackoff, leaseDuration, pollInterval,
                leaseBatchSize, retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with {@code value} attempts */
    public TransportSettings withMaxAttempts(int value) {
        return new TransportSettings(workers, value, initialBackoff, maxBackoff, leaseDuration, pollInterval, leaseBatchSize,
                retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with the given backoff bounds */
    public TransportSettings withBackoff(Duration initial, Duration max) {
        return new TransportSettings(workers, maxAttempts, initial, max, leaseDuration, pollInterval, leaseBatchSize,
                retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with {@code value} as lease duration */
    public TransportSettings withLeaseDuration(Duration value) {
        return new TransportSettings(workers, maxAttempts, initialBackoff, maxBackoff, value, pollInterval,
                leaseBatchSize, retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with {@code value} as poll interval */
    public TransportSettings withPollInterval(Duration value) {
        return new TransportSettings(workers, maxAttempts, initialBackoff, maxBackoff, leaseDuration, value, leaseBatchSize,
                retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with {@code value} as lease batch size */
    public TransportSettings withLeaseBatchSize(int value) {
        return new TransportSettings(workers, maxAttempts, initialBackoff, maxBackoff, leaseDuration, pollInterval, value,
                retention, retentionSweepInterval, heartbeatInterval);
    }

    /** @return these settings with the given retention and sweep interval */
    public TransportSettings withRetention(Duration value, Duration sweepInterval) {
        return new TransportSettings(workers, maxAttempts, initialBackoff, maxBackoff, leaseDuration, pollInterval,
                leaseBatchSize, value, sweepInterval, heartbeatInterval);
    }

    /** @return these settings with {@code value} as heartbeat interval */
    public TransportSettings withHeartbeatInterval(Duration value) {
        return new TransportSettings(workers, maxAttempts, initialBackoff, maxBackoff, leaseDuration, pollInterval,
                leaseBatchSize, retention, retentionSweepInterval, value);
    }

    private static void positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive, but is " + value);
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration, but is " + value);
        }
    }
}
