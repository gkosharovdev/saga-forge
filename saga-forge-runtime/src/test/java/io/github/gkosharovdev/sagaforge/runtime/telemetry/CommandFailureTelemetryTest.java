package io.github.gkosharovdev.sagaforge.runtime.telemetry;

import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.report;
import static io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions.attribute;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ShipParcel;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code saga_forge.handle} span of a command failure report (specs/012
 * contracts/runtime-reaction.md).
 */
class CommandFailureTelemetryTest {

  private static final AttributeKey<String> TRIGGER =
      AttributeKey.stringKey("saga_forge.compensation.trigger");
  private static final AttributeKey<String> COMMAND_TYPE =
      AttributeKey.stringKey("saga_forge.command.type");
  private static final AttributeKey<String> OUTCOME =
      AttributeKey.stringKey("saga_forge.handling.outcome");
  private static final AttributeKey<String> SAGA_TYPE =
      AttributeKey.stringKey("saga_forge.saga.type");
  private static final AttributeKey<String> SAGA_OUTCOME =
      AttributeKey.stringKey("saga_forge.saga.outcome");

  private final TelemetryAssertions telemetry = new TelemetryAssertions();

  @Test
  @DisplayName(
      "a report opens a handle span under the delivery's span, with the compensations below it")
  void handleSpanUnderDelivery() {
    ParcelFixture fixture = new ParcelFixture(telemetry.openTelemetry());
    fixture.packed("p-1");
    telemetry.reset();
    Span delivery = telemetry.openTelemetry().getTracer("test").spanBuilder("delivery").startSpan();
    try (Scope ignored = delivery.makeCurrent()) {
      fixture
          .transport()
          .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));
    } finally {
      delivery.end();
    }

    SpanData handle = telemetry.only("saga_forge.handle");
    assertThat(handle.getParentSpanId()).isEqualTo(delivery.getSpanContext().getSpanId());
    assertThat(attribute(handle, TRIGGER)).isEqualTo("COMMAND_FAILED");
    assertThat(attribute(handle, COMMAND_TYPE)).isEqualTo(ShipParcel.class.getName());
    assertThat(attribute(handle, OUTCOME)).isEqualTo("COMPENSATED");
    assertThat(telemetry.childrenOf(handle, "saga_forge.compensation")).hasSize(3);
    assertThat(
            telemetry.counter(
                "saga_forge.saga.ended",
                Attributes.of(SAGA_TYPE, ParcelSaga.class.getName(), SAGA_OUTCOME, "compensated")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("an ignored report is marked IGNORED")
  void ignoredReport() {
    ParcelFixture fixture = new ParcelFixture(telemetry.openTelemetry());
    fixture.packed("p-1");
    fixture
        .transport()
        .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));
    telemetry.reset();

    fixture
        .transport()
        .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));

    assertThat(attribute(telemetry.only("saga_forge.handle"), OUTCOME)).isEqualTo("IGNORED");
  }
}
