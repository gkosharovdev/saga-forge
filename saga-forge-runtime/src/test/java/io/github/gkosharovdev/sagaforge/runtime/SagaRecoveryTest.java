package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.operations.SagaStartAbandonedException;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Recovery sweeps and start-recovery ({@code specs/015-saga-operations} contracts/operations-api.md
 * SW1, SW2, SW4, SW6 to SW9, ST1, ST3 and CP4; tasks.md T032, T036).
 */
class SagaRecoveryTest {

  /** A start whose first unit of work crashed after the creation write: {@code CREATED}. */
  static String strandedStart(UnwindFixture fixture, String orderId) {
    CompensationFaults faults = fixture.withFaults();
    faults.crashOnLock(1);
    assertThat(catchThrowable(() -> fixture.start(orderId))).isNotNull();
    fixture.restart();
    SagaInstance stranded = fixture.operations().findByAssociation("orderId", orderId).get(0);
    assertThat(stranded.status()).isEqualTo(SagaStatus.CREATED);
    return stranded.sagaId();
  }

  @Test
  @DisplayName("SW1/SW2: a COMPENSATING instance older than the stall age is resumed by recover()")
  void stalledCompensationIsResumed() {
    UnwindFixture fixture = SagaRepairTest.threeCommitted();
    String sagaId = SagaRepairTest.crashAfterFirstCompensation(fixture);
    fixture.restart();

    fixture.advance(Duration.ofMinutes(1));
    assertThat(fixture.operations().recover()).isEqualTo(RecoveryReport.none());
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATING);

    fixture.advance(Duration.ofMinutes(5));
    RecoveryReport report = fixture.operations().recover();

    assertThat(report.compensationsResumed()).isEqualTo(1);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    SagaLifecycleEntry resumed =
        fixture.operations().history(sagaId).stream()
            .filter(entry -> entry.kind() == SagaLifecycleEventKind.COMPENSATION_RESUMED)
            .findFirst()
            .orElseThrow();
    assertThat(resumed.operator().operator()).isEqualTo(OperatorRequest.RECOVERY_OPERATOR);
    assertThat(fixture.logger().byEvent("saga.recovery.compensationResumed")).hasSize(1);
  }

  @Test
  @DisplayName("SW4: an error on one instance is counted and logged, the sweep goes on")
  void errorOnOneInstance() {
    UnwindFixture fixture = SagaRepairTest.threeCommitted();
    String sagaId = SagaRepairTest.crashAfterFirstCompensation(fixture);
    fixture.advance(Duration.ofMinutes(6));
    CompensationFaults faults = fixture.withFaults();
    faults.crashOnLock(faults.locks() + 1);

    RecoveryReport report = fixture.operations().recover();

    assertThat(report.errors()).isEqualTo(1);
    assertThat(fixture.logger().byEvent("saga.recovery.failed")).hasSize(1);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATING);
  }

  @Test
  @DisplayName("SW6: an abandoned start is reported once per abandonment age, never changed")
  void abandonedStartReported() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    String sagaId = strandedStart(fixture, "o-1");

    assertThat(fixture.operations().recover()).isEqualTo(RecoveryReport.none());
    fixture.advance(Duration.ofMinutes(20));
    assertThat(fixture.operations().recover().startsReported()).isEqualTo(1);
    assertThat(fixture.operations().recover().startsReported()).isZero();
    fixture.advance(Duration.ofMinutes(15));
    assertThat(fixture.operations().recover().startsReported()).isEqualTo(1);

    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.CREATED);
    assertThat(fixture.logger().byEvent("saga.recovery.startAbandoned")).hasSize(2);
  }

  @Test
  @DisplayName("SW7/SW8: policy FAIL ends the start FAILED; its redelivered event starts anew")
  void abandonedStartFailed() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    String sagaId = strandedStart(fixture, "o-1");
    fixture.withRecovery(
        RecoverySettings.defaults().withAbandonedStartPolicy(AbandonedStartPolicy.FAIL));
    fixture.advance(Duration.ofMinutes(20));

    RecoveryReport report = fixture.operations().recover();

    assertThat(report.startsFailed()).isEqualTo(1);
    SagaInstance failed = fixture.committed(sagaId);
    assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
    assertThat(failed.failure().causeType()).isEqualTo(SagaStartAbandonedException.class.getName());
    assertThat(failed.failure().classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
    assertThat(fixture.outbox().outcomes().stream().filter(SagaFailed.class::isInstance).toList())
        .hasSize(1);

    String restarted = fixture.start("o-1");

    assertThat(restarted).isNotEqualTo(sagaId);
    assertThat(fixture.committed(restarted).status()).isEqualTo(SagaStatus.RUNNING);
  }

  @Test
  @DisplayName("ST1: a redelivered start event adopts the CREATED instance")
  void redeliveredStartAdoptsStrandedInstance() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    String sagaId = strandedStart(fixture, "o-1");

    String handled = fixture.start("o-1");

    assertThat(handled).isEqualTo(sagaId);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(fixture.operations().findByAssociation("orderId", "o-1")).hasSize(1);
    assertThat(fixture.operations().history(sagaId).stream().map(SagaLifecycleEntry::kind).toList())
        .contains(SagaLifecycleEventKind.START_RECOVERED);
    assertThat(fixture.logger().byEvent("saga.start.recovered")).hasSize(1);
  }

  @Test
  @DisplayName("ST3: a RUNNING instance with the same association is still a duplicate start")
  void runningIsDuplicateStart() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    fixture.start("o-1");

    assertThat(
            fixture
                .receive(
                    new io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation
                        .UnwindStarted("o-1"))
                .outcome())
        .isEqualTo(io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome.DUPLICATE_START);
  }

  @Test
  @DisplayName("CP4: compensating a CREATED instance runs nothing and ends COMPENSATED at once")
  void compensateCreated() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    String sagaId = strandedStart(fixture, "o-1");

    RepairResult result = fixture.operations().compensate(sagaId, SagaRepairTest.ALICE);

    assertThat(((RepairResult.Applied) result).instance().status())
        .isEqualTo(SagaStatus.COMPENSATED);
    assertThat(SagaRepairTest.compensations(fixture)).isEmpty();
    assertThat(fixture.outbox().outcomes()).hasSize(1);
  }

  @Test
  @DisplayName("SW9: enabled sweeps run on saga-forge-recovery; stop() ends the thread")
  void scheduledSweeps() throws InterruptedException {
    UnwindFixture fixture = SagaRepairTest.threeCommitted();
    String sagaId = SagaRepairTest.crashAfterFirstCompensation(fixture);
    fixture.advance(Duration.ofMinutes(6));

    fixture.withRecovery(
        RecoverySettings.enabledWithDefaults().withInterval(Duration.ofMillis(20)));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (fixture.committed(sagaId).status() != SagaStatus.COMPENSATED
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(threadAlive()).isTrue();
    fixture.engine().stop();
    assertThat(threadAlive()).isFalse();
    assertThat(catchThrowable(() -> fixture.operations()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("restart: start() after stop() resumes the sweeps and accepts work again")
  void restartAfterStop() {
    UnwindFixture fixture = SagaRepairTest.threeCommitted();
    fixture.withRecovery(RecoverySettings.enabledWithDefaults().withInterval(Duration.ofHours(1)));
    fixture.engine().stop();
    assertThat(threadAlive()).isFalse();
    assertThat(fixture.engine().isStarted()).isFalse();

    fixture.engine().start();

    assertThat(fixture.engine().isStarted()).isTrue();
    assertThat(threadAlive()).isTrue();
    String sagaId = fixture.start("o-restarted");
    assertThat(fixture.operations().find(sagaId)).isPresent();
    assertThat(catchThrowable(fixture.engine()::start)).isInstanceOf(IllegalStateException.class);
    fixture.engine().stop();
    assertThat(threadAlive()).isFalse();
  }

  private static boolean threadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().equals("saga-forge-recovery") && thread.isAlive());
  }

  @Test
  @DisplayName("O1/O2/O5/O7: queries read what storage holds, context only through context()")
  void queries() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    String sagaId = fixture.start("o-1");

    SagaInstance found = fixture.operations().find(sagaId).orElseThrow();
    assertThat(found).isEqualTo(fixture.committed(sagaId));
    assertThat(found.state()).isEmpty();
    assertThat(fixture.operations().find("nope")).isEmpty();
    assertThat(fixture.operations().history("nope")).isEmpty();
    assertThat(fixture.operations().context("nope")).isEmpty();
    assertThat(fixture.operations().count(java.util.Set.of()).get(SagaStatus.RUNNING)).isEqualTo(1);
    assertThat(
            fixture
                .operations()
                .list(
                    io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery.all()
                        .withStatuses(SagaStatus.RUNNING))
                .instances())
        .extracting(SagaInstance::sagaId)
        .containsExactly(sagaId);
    List<SagaLifecycleEntry> history = fixture.operations().history(sagaId);
    assertThat(history.get(0).kind()).isEqualTo(SagaLifecycleEventKind.CREATED);
  }
}
