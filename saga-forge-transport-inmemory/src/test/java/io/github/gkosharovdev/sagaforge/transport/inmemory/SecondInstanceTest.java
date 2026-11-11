package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;

/** A second instance on the same log by mistake (FR-021, quickstart scenario 13, tasks.md T040). */
class SecondInstanceTest {

    @Test
    @DisplayName("the second instance warns, and every message is still delivered")
    void warnsAndLosesNothing() {
        TransportSettings settings = TransportSettings.defaults().withPollInterval(Duration.ofMillis(20));
        TransportUnderTest first = new TransportUnderTest(settings, event -> new HandlingReport(event, List.of()))
                .start();
        TransportUnderTest second = new TransportUnderTest(first.boundary, first.log, settings,
                event -> new HandlingReport(event, List.of())).start();
        try {
            assertThat(first.logger.byEvent("saga.transport.secondInstance")).isEmpty();
            assertThat(second.logger.byEvent("saga.transport.secondInstance")).hasSize(1);

            for (int i = 0; i < 50; i++) {
                (i % 2 == 0 ? first : second).publish(new Unrouted("o-" + i));
            }

            await(() -> first.log.all().size() == 50 && first.log.all().stream()
                    .allMatch(m -> m.status() == MessageStatus.CONSUMED), Duration.ofSeconds(5), "all consumed");
            Set<Object> delivered = new HashSet<>(first.receiver.delivered());
            delivered.addAll(second.receiver.delivered());
            assertThat(delivered).hasSize(50);
        } finally {
            first.close();
            second.close();
        }
    }
}
