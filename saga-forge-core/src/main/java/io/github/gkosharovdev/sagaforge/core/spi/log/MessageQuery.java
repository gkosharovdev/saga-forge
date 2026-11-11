package io.github.gkosharovdev.sagaforge.core.spi.log;

import java.util.EnumSet;
import java.util.Set;

/**
 * A read-only selection of logged messages ({@code specs/007-in-memory-transport} FR-022). Criteria combine with
 * "and"; an absent criterion matches everything. Results are ordered by position.
 *
 * @param statuses       statuses to include; empty means all
 * @param sourceSagaType only messages produced by this saga type; both or neither with {@code sourceSagaId}
 * @param sourceSagaId   only messages produced by this saga instance
 * @param orderingKey    only messages with this ordering key; may be {@code null}
 * @param payloadType    only messages of this payload type; may be {@code null}
 * @param limit          at most this many, 1 to {@link #MAX_LIMIT}
 * @param includePayload whether to decode payloads; an undecodable payload is then left out, never thrown
 */
public record MessageQuery(Set<MessageStatus> statuses, String sourceSagaType, String sourceSagaId,
        String orderingKey, String payloadType, int limit, boolean includePayload) {

    /** The largest {@link #limit()}. */
    public static final int MAX_LIMIT = 1_000;

    /** The default {@link #limit()}. */
    public static final int DEFAULT_LIMIT = 100;

    public MessageQuery {
        Checks.nonNull(statuses, "statuses");
        statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
        Checks.bothOrNeither(sourceSagaType, sourceSagaId, "sourceSagaType", "sourceSagaId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ", but is " + limit);
        }
    }

    /** @return a builder matching everything, {@link #DEFAULT_LIMIT} results, without payloads */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param message a message; non-null
     * @return whether {@code message} meets every criterion (the limit aside)
     */
    public boolean matches(LoggedMessage message) {
        return (statuses.isEmpty() || statuses.contains(message.status()))
                && (sourceSagaId == null || (sourceSagaType.equals(message.sourceSagaType())
                        && sourceSagaId.equals(message.sourceSagaId())))
                && (orderingKey == null || orderingKey.equals(message.orderingKey()))
                && (payloadType == null || payloadType.equals(message.payloadType()));
    }

    /** Builds a {@link MessageQuery}. */
    public static final class Builder {

        private Set<MessageStatus> statuses = Set.of();
        private String sourceSagaType;
        private String sourceSagaId;
        private String orderingKey;
        private String payloadType;
        private int limit = DEFAULT_LIMIT;
        private boolean includePayload;

        private Builder() {
        }

        public Builder statuses(MessageStatus first, MessageStatus... more) {
            this.statuses = EnumSet.of(first, more);
            return this;
        }

        public Builder sourceSaga(String sagaType, String sagaId) {
            this.sourceSagaType = sagaType;
            this.sourceSagaId = sagaId;
            return this;
        }

        public Builder orderingKey(String key) {
            this.orderingKey = key;
            return this;
        }

        public Builder payloadType(String type) {
            this.payloadType = type;
            return this;
        }

        public Builder limit(int max) {
            this.limit = max;
            return this;
        }

        public Builder includePayload(boolean include) {
            this.includePayload = include;
            return this;
        }

        public MessageQuery build() {
            return new MessageQuery(statuses, sourceSagaType, sourceSagaId, orderingKey, payloadType, limit,
                    includePayload);
        }
    }
}
