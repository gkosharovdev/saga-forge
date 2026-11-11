package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;

/** Graceful shutdown (FR-020, tasks.md T040). */
class ShutdownTest {

    private static final TransportSettings SETTINGS = TransportSettings.defaults()
            .withPollInterval(Duration.ofMillis(20));

    @Test
    @DisplayName("a delivery that finishes within the timeout is consumed")
    void finishedDeliveryConsumed() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        TransportUnderTest t = new TransportUnderTest(SETTINGS, event -> {
            entered.countDown();
            sleep(100);
            return new HandlingReport(event, List.of());
        }).start();
        t.publish(new Unrouted("o-1"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        t.transport.stop(Duration.ofSeconds(2));

        assertThat(t.log.all().get(0).status()).isEqualTo(MessageStatus.CONSUMED);
        assertThat(t.transport.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a delivery still running at the timeout stays unconsumed, its lease released; stop is idempotent")
    void unfinishedDeliveryReleased() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransportUnderTest t = new TransportUnderTest(SETTINGS, event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("killed");
        }).start();
        t.publish(new Unrouted("o-1"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        t.transport.stop(Duration.ofMillis(50));
        t.transport.stop(Duration.ofMillis(50));

        assertThat(t.log.all().get(0).status()).isEqualTo(MessageStatus.UNCONSUMED);
        assertThat(t.log.all().get(0).leasedBy()).isNull();
        release.countDown();
        TransportUnderTest next = new TransportUnderTest(t.boundary, t.log, SETTINGS,
                event -> new HandlingReport(event, List.of())).start();
        try {
            await(() -> t.log.all().get(0).status() == MessageStatus.CONSUMED, Duration.ofSeconds(5),
                    "the next transport to deliver it");
        } finally {
            next.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
