package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentDesk;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.TakePayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.StampSaga;

/** User Story 5: supplied saga and handler instances (specs/009-saga-test-fixture tasks.md T054). */
class SuppliedInstancesTest {

    private static final Instant NOON = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    @DisplayName("scenario 1: a supplied saga factory yields a fresh object per unit of work")
    void sagaFactory() {
        AtomicInteger created = new AtomicInteger();
        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        SagaFixture fixture = SagaFixture.of(StampSaga.class)
                .sagaObjects(StampSaga.class, () -> {
                    created.incrementAndGet();
                    return new StampSaga(clock);
                })
                .build();

        fixture.given(new OrderPlaced("o-1", "10"))
                .when(new StockReserved("o-1"))
                .expectEndedWith("reserved")
                .expectContext("placedAt", NOON.toString())
                .expectContext("reservedAt", NOON.toString());

        assertThat(created).hasValue(2);
    }

    @Test
    @DisplayName("scenario 1: a handler instance with its own collaborators is used as given")
    void handlerInstance() {
        PaymentDesk desk = new PaymentDesk();

        SagaFixture.of(ShopSaga.class).handlers(desk).build()
                .when(new OrderPlaced("o-1", "10"))
                .expectCommands(new TakePayment("o-1", "10"), new ReserveStock("o-1"))
                .expectStatus(SagaStatus.RUNNING);

        assertThat(desk.payments()).hasSize(1);
    }

    @Test
    @DisplayName("a saga without a no-argument constructor and without a factory fails setup")
    void missingFactory() {
        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class,
                () -> SagaFixture.of(StampSaga.class).build());

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("StampSaga");
    }
}
