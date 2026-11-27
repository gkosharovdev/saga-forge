package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.test.fixtures.Packing.LabelSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Packing.PackingDesk;
import io.github.gkosharovdev.sagaforge.test.fixtures.Packing.PackingRequested;
import io.github.gkosharovdev.sagaforge.test.fixtures.Packing.PackingSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Packing.Parcel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixture delivers a recorded event its own instance ran in process to the other saga types,
 * with its source, like the in-memory transport (specs/014-publish-once Property 47, FR-018).
 */
class RecordedEventFanOutFixtureTest {

  @Test
  @DisplayName(
      "an event PackingSaga ran in process starts LabelSaga once; PackingSaga sees it once")
  void fansOut() {
    SagaFixture fixture =
        SagaFixture.of(PackingSaga.class, LabelSaga.class, Parcel.class)
            .handlers(new PackingDesk())
            .build();

    EventResult result = fixture.when(new PackingRequested("o-1")).result();

    List<SagaHandlingResult> all =
        result.reports().stream().flatMap(report -> report.results().stream()).toList();
    assertThat(all)
        .extracting(SagaHandlingResult::sagaType, SagaHandlingResult::outcome)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                PackingSaga.class.getName(), HandlingOutcome.COMPLETED),
            org.assertj.core.groups.Tuple.tuple(
                LabelSaga.class.getName(), HandlingOutcome.WAITING));
    assertThat(all)
        .filteredOn(handled -> handled.sagaType().equals(LabelSaga.class.getName()))
        .singleElement()
        .satisfies(label -> assertThat(label.started()).isTrue());
  }
}
