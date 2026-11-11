package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;

/** {@link InMemoryMessageStore} against the {@link MessageStoreContract}, plus its test hooks (tasks.md T020). */
class InMemoryMessageStoreTest extends MessageStoreContract {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final InMemoryMessageStore log = new InMemoryMessageStore();

    @Override
    protected MessageStore log() {
        return log;
    }

    @Override
    protected TransactionBoundary transactions() {
        return boundary;
    }

    @Override
    protected void makeUnreadable(String messageId) {
        log.markUnreadable(messageId);
    }

    @Test
    @DisplayName("failNext throws once from the named operation; pauseLeasing hides everything")
    void hooks() {
        IllegalStateException failure = new IllegalStateException("db down");
        log.failNext(InMemoryMessageStore.Operation.APPEND, failure);

        assertThatThrownBy(() -> appendCommitted(event("a", "k1"))).isSameAs(failure);
        appendCommitted(event("a", "k1"));
        log.pauseLeasing(true);
        assertThat(log.lease("c", T0, LEASE, 10).isEmpty()).isTrue();
        log.pauseLeasing(false);
        assertThat(log.lease("c", T0, LEASE, 10).readable()).hasSize(1);
    }
}
