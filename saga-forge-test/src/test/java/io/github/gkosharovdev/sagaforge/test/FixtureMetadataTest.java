package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingRequested;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.DispatchSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.Notifier;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.Parcel;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.ParcelPacked;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.ParcelStore;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 4 ({@code specs/019-process-metadata} T040): a fixture test gives an event metadata,
 * or an accessor that captures it, and expects what the instance keeps and what every command,
 * in-transaction or outbox, is handled with.
 */
class FixtureMetadataTest {

  private static final SagaMetadata C1 = SagaMetadata.of(Map.of("correlationId", "c-1"));
  private static final SagaMetadata C2 = SagaMetadata.of(Map.of("correlationId", "c-2"));

  @Test
  @DisplayName("when(event, metadata): the instance keeps it and every command sees it")
  void whenWithMetadata() {
    SagaFixture fixture =
        SagaFixture.of(DispatchSaga.class, Parcel.class)
            .handlers(new ParcelStore().with(new Parcel("p-1")), new Notifier())
            .build();

    Then then = fixture.when(new ParcelPacked("p-1"), C1).expectMetadata(C1);

    assertThat(then.result().commands())
        .hasSize(2)
        .extracting(RecordedCommand::metadata)
        .containsOnly(C1);
    assertThat(fixture.instance(DispatchSaga.class, "p-1").metadata()).isEqualTo(C1);
  }

  @Test
  @DisplayName("given(metadata, ...): a later event's metadata does not replace the instance's")
  void givenWithMetadata() {
    SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();

    fixture
        .given(C1, new OrderPlaced("o-1", "10"))
        .when(new PaymentTaken("payment-1", "o-1", "10"), C2)
        .expectMetadata(C1);
  }

  @Test
  @DisplayName("Builder.metadataAccessor: when(event) captures what the test thread holds")
  void accessorCaptures() {
    ThreadLocalMetadataAccessor accessor = new ThreadLocalMetadataAccessor();
    SagaFixture fixture = SagaFixture.of(ShopSaga.class).metadataAccessor(accessor).build();
    accessor.set(C1.asMap());
    try {
      fixture.when(new OrderPlaced("o-1", "10")).expectMetadata(C1);
    } finally {
      accessor.clear();
    }
    assertThat(fixture.commands())
        .isNotEmpty()
        .extracting(RecordedCommand::metadata)
        .containsOnly(C1);
  }

  @Test
  @DisplayName("without metadata or an accessor, instances keep empty metadata")
  void noMetadata() {
    SagaFixture.of(ShopSaga.class)
        .build()
        .when(new OrderPlaced("o-1", "10"))
        .expectMetadata(SagaMetadata.empty());
  }

  @Test
  @DisplayName("a failed expectMetadata names the expected and the actual entries")
  void failureMessage() {
    Then then = SagaFixture.of(ShopSaga.class).build().when(new OrderPlaced("o-1", "10"), C1);

    assertThatThrownBy(() -> then.expectMetadata(C2))
        .isInstanceOf(SagaExpectationError.class)
        .hasMessageContaining("correlationId=c-2")
        .hasMessageContaining("correlationId=c-1");
    assertThatThrownBy(() -> then.expectMetadata(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "a deadline is delivered with its instance's metadata, and its timeout step's entry keeps it")
  void deadlineCarriesMetadata() {
    SagaFixture fixture = SagaFixture.of(BookingSaga.class, Booking.Seat.class).build();
    fixture.given(C1, new BookingRequested("b-1", "PT30M"));

    fixture
        .whenTimeElapses(Duration.ofMinutes(30))
        .expectDeadlineExpired(Booking.HOLD)
        .expectMetadata(C1);

    assertThat(fixture.instance(BookingSaga.class, "b-1").history())
        .filteredOn(entry -> "holdExpired".equals(entry.stepName()))
        .isNotEmpty()
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
  }
}
