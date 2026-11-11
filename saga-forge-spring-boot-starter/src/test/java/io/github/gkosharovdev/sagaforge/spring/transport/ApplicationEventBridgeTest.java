package io.github.gkosharovdev.sagaforge.spring.transport;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageLog;

/** FR-027 (tasks.md T074): delivered events are re-published as application events; listener failures are ignored. */
class ApplicationEventBridgeTest {

    @Test
    @DisplayName("each delivered event is published once; a failing publisher changes nothing")
    void republishes() {
        InMemoryMessageLog log = new InMemoryMessageLog();
        InMemoryTransport transport = InMemoryTransport.builder().log(log)
                .transactions(new InMemoryTransactionBoundary())
                .settings(TransportSettings.defaults().withPollInterval(Duration.ofMillis(20))).build();
        List<Object> published = new CopyOnWriteArrayList<>();
        new ApplicationEventBridge(transport, event -> {
            published.add(event);
            if (((Unrouted) event).orderId().equals("listener-fails")) {
                throw new IllegalStateException("listener failed");
            }
        });
        transport.start(event -> new HandlingReport(event, List.of()), null);
        try {
            transport.dispatcher().dispatch(new Unrouted("listener-fails"));
            transport.dispatcher().dispatch(new Unrouted("fine"));

            await(() -> published.size() == 2, Duration.ofSeconds(5), "both publications");
            assertThat(published).containsExactlyInAnyOrder(new Unrouted("listener-fails"), new Unrouted("fine"));
            assertThat(log.all()).allSatisfy(m -> assertThat(m.status().name()).isEqualTo("CONSUMED"));
        } finally {
            transport.stop(Duration.ofSeconds(2));
        }
    }
}
