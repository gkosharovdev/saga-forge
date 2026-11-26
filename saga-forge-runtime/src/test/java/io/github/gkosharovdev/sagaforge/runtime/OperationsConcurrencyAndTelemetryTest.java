package io.github.gkosharovdev.sagaforge.runtime;

import static io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions.attribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.support.TelemetryAssertions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repairs racing each other, batched sweeps, the recovery settings and the operations telemetry
 * ({@code specs/015-saga-operations} contracts/operations-api.md R6, SW3; tasks.md T030, T035,
 * T050).
 */
class OperationsConcurrencyAndTelemetryTest {

  private static final OperatorRequest ALICE = SagaRepairTest.ALICE;

  @Test
  @DisplayName("R6: two concurrent resumes run every compensation once; the second is not applied")
  void concurrentResumes() throws Exception {
    UnwindFixture fixture = SagaRepairTest.threeCommitted();
    String sagaId = SagaRepairTest.compensationFailed(fixture);
    fixture.journal().clearFailures();
    CountDownLatch go = new CountDownLatch(1);
    Callable<RepairResult> resume =
        () -> {
          go.await();
          return fixture.operations().resumeCompensation(sagaId, ALICE);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<RepairResult>> results = new ArrayList<>();
      results.add(pool.submit(resume));
      results.add(pool.submit(resume));
      go.countDown();
      List<RepairResult> done = new ArrayList<>();
      for (Future<RepairResult> result : results) {
        done.add(result.get());
      }

      assertThat(done).filteredOn(RepairResult.Applied.class::isInstance).hasSize(1);
      assertThat(done).filteredOn(RepairResult.NotApplied.class::isInstance).hasSize(1);
      assertThat(SagaRepairTest.compensations(fixture))
          .containsExactly("undoC", "undoB", "undoB", "undoA");
      assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("SW3: a sweep handles at most batchSize stalled instances, later sweeps the rest")
  void batchedSweeps() throws InterruptedException {
    ParcelFixture fixture = new ParcelFixture();
    fixture.withRecovery(
        RecoverySettings.defaults().withStallAge(Duration.ofMillis(1)).withBatchSize(2));
    for (int i = 1; i <= 5; i++) {
      String sagaId = fixture.packed("p-" + i);
      fixture.compensating(sagaId, Set.of());
    }
    Thread.sleep(5);

    assertThat(fixture.engine().operations().recover().compensationsResumed()).isEqualTo(2);
    assertThat(fixture.engine().operations().recover().compensationsResumed()).isEqualTo(2);
    assertThat(fixture.engine().operations().recover().compensationsResumed()).isEqualTo(1);
    assertThat(fixture.engine().operations().recover().compensationsResumed()).isZero();
    assertThat(fixture.engine().operations().count(Set.of()).get(SagaStatus.COMPENSATED))
        .isEqualTo(5);
    assertThat(ParcelSaga.ran()).hasSize(15);
  }

  @Test
  @DisplayName(
      "T035: settings reject non-positive durations, batch sizes outside 1..1000, null policy")
  void settingsValidation() {
    RecoverySettings defaults = RecoverySettings.defaults();

    assertThat(defaults.enabled()).isFalse();
    assertThat(RecoverySettings.enabledWithDefaults().enabled()).isTrue();
    assertThat(defaults.abandonedStartPolicy()).isEqualTo(AbandonedStartPolicy.REPORT);
    assertThatThrownBy(() -> defaults.withInterval(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.withStallAge(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.withAbandonmentAge(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.withBatchSize(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.withBatchSize(RecoverySettings.MAX_BATCH_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults.withAbandonedStartPolicy(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SagaRuntime.builder().recovery(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("T050: a repair opens saga_forge.operation and counts saga_forge.operations")
  void operationTelemetry() {
    TelemetryAssertions telemetry = new TelemetryAssertions();
    ParcelFixture fixture = new ParcelFixture(telemetry.openTelemetry());
    String sagaId = fixture.packed("p-1");
    telemetry.reset();

    fixture.engine().operations().compensate(sagaId, ALICE);
    fixture.engine().operations().markResolved(sagaId, ALICE);

    List<SagaData> operations =
        telemetry.named("saga_forge.operation").stream().map(SagaData::new).toList();
    assertThat(operations)
        .extracting(SagaData::action, SagaData::result, SagaData::operator)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("compensate", "APPLIED", "alice"),
            org.assertj.core.groups.Tuple.tuple("resolve", "WRONG_STATUS", "alice"));
    assertThat(
            telemetry.counter(
                "saga_forge.operations",
                Attributes.of(
                    AttributeKey.stringKey("saga_forge.operation.action"),
                    "compensate",
                    AttributeKey.stringKey("saga_forge.operation.result"),
                    "APPLIED")))
        .isEqualTo(1);
    assertThat(telemetry.metric("saga_forge.instances")).isPresent();
  }

  private record SagaData(String action, String result, String operator) {
    SagaData(SpanData span) {
      this(
          attribute(span, AttributeKey.stringKey("saga_forge.operation.action")),
          attribute(span, AttributeKey.stringKey("saga_forge.operation.result")),
          attribute(span, AttributeKey.stringKey("saga_forge.operator")));
    }
  }
}
