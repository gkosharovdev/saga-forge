package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.SpanData;

/** FR-017: fixture tests may assert on the runtime's telemetry (specs/010-otel-observability tasks.md T038). */
class TelemetryTest {

    private static final AttributeKey<String> SAGA_TYPE = AttributeKey.stringKey("saga_forge.saga.type");

    @Test
    @DisplayName("a fixture given an OpenTelemetry SDK reports the span tree and counters of a scenario")
    void spansAndCounters() {
        TelemetryAssertions telemetry = new TelemetryAssertions();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).openTelemetry(telemetry.openTelemetry()).build();

        fixture.given(new OrderPlaced("o-1", "10"))
                .when(new PaymentTaken("payment-1", "o-1", "10"))
                .expectCompletedStep(Shop.PAID)
                .expectStatus(SagaStatus.RUNNING);

        SpanData handle = telemetry.named("saga_forge.handle").get(1);
        SpanData attempt = telemetry.childrenOf(handle, "saga_forge.attempt").get(0);
        assertThat(telemetry.childrenOf(attempt, "saga_forge.step")).hasSize(1);
        assertThat(telemetry.counter("saga_forge.saga.started",
                Attributes.of(SAGA_TYPE, ShopSaga.class.getName()))).isEqualTo(1);
    }

    @Test
    @DisplayName("without an OpenTelemetry the fixture behaves as before")
    void withoutTelemetry() {
        SagaFixture.of(ShopSaga.class).build()
                .when(new OrderPlaced("o-1", "10"))
                .expectStatus(SagaStatus.RUNNING);
    }
}
