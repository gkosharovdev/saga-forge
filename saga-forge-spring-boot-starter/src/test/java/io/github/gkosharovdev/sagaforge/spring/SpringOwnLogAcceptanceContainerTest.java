package io.github.gkosharovdev.sagaforge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;

/** SC-008, setup 2 (tasks.md T073): Spring Boot, Saga Forge's own Postgres log, no Spring Modulith. */
@Tag("container")
class SpringOwnLogAcceptanceContainerTest extends TransportAcceptanceSuite {

    @Override
    protected Setup newSetup() {
        return new SpringSetup(false);
    }

    @Test
    @DisplayName("the starter wires Saga Forge's own Postgres log")
    void ownLog() {
        assertThat(((SpringSetup) setup).context().getBean(MessageStore.class)).isInstanceOf(PostgresMessageStore.class);
    }
}
