package io.github.gkosharovdev.sagaforge.spring.modulith;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.log.MessageLog;
import io.github.gkosharovdev.sagaforge.spring.SpringSetup;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;

/**
 * SC-008, setup 3 (tasks.md T073): Spring Boot with Spring Modulith's JDBC event publication registry. The registry
 * holds the messages; Saga Forge's delivery records hold no payload (FR-028a).
 */
@Tag("container")
class ModulithInteropAcceptanceContainerTest extends TransportAcceptanceSuite {

    @Override
    protected Setup newSetup() {
        return new SpringSetup(true);
    }

    @Test
    @DisplayName("FR-028a: one outbox - payloads in the registry, completed there; delivery records without payload")
    void oneOutbox() {
        SpringSetup spring = (SpringSetup) setup;
        assertThat(spring.context().getBean(MessageLog.class)).isInstanceOf(ModulithMessageLog.class);

        setup.publish(new OrderPlaced("o-1"));
        await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        awaitQuiet();

        List<Map<String, Object>> records = spring.jdbc().queryForList(
                "SELECT payload, external_ref, status FROM saga_message_log");
        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.get("payload")).isNull();
            assertThat(row.get("external_ref")).isNotNull();
            assertThat(row.get("status")).isEqualTo("CONSUMED");
        });
        List<Map<String, Object>> publications = spring.jdbc().queryForList(
                "SELECT id::text AS id, listener_id, completion_date FROM event_publication");
        assertThat(publications).singleElement().satisfies(row -> {
            assertThat(row.get("id")).isEqualTo(records.get(0).get("external_ref"));
            assertThat(row.get("listener_id")).isEqualTo(ModulithMessageLog.TARGET.getValue());
            assertThat(row.get("completion_date")).isNotNull();
        });
    }
}
