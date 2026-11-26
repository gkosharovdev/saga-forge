package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairRejection;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Finished;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The repair actions of {@code SagaOperations} ({@code specs/015-saga-operations}
 * contracts/operations-api.md cases R2 to R5, RC1 to RC3, RC5 to RC7, CP1, CP2, CP4 to CP6 and MR1
 * to MR4; tasks.md T026, T044, T045, T049), over {@link UnwindSaga}.
 */
class SagaRepairTest {

  static final OperatorRequest ALICE = new OperatorRequest("alice", "refund confirmed by hand");

  /** A refusal the provider marks final. */
  static final class RefundRefused extends RuntimeException implements FinalFailure {

    RefundRefused() {
      super("refund refused");
    }
  }

  static List<String> compensations(UnwindFixture fixture) {
    return fixture.journal().compensations().stream()
        .filter(name -> name.startsWith("undo"))
        .toList();
  }

  static UnwindFixture threeCommitted() {
    UnwindFixture fixture = UnwindFixture.withEveryCompensation();
    fixture.start("o-1");
    fixture.receive(new BDone("o-1"));
    fixture.receive(new CDone("o-1"));
    return fixture;
  }

  /** C compensated, B's compensation failed: {@code COMPENSATION_FAILED}. */
  static String compensationFailed(UnwindFixture fixture) {
    fixture.journal().failOn("undoB", new RefundRefused());
    String sagaId = fixture.receive(new Aborted("o-1")).sagaId();
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
    return sagaId;
  }

  static <T extends SagaOutcome> List<T> outcomes(UnwindFixture fixture, Class<T> type) {
    return fixture.outbox().outcomes().stream().filter(type::isInstance).map(type::cast).toList();
  }

  static List<SagaLifecycleEventKind> kinds(UnwindFixture fixture, String sagaId) {
    return fixture.operations().history(sagaId).stream().map(SagaLifecycleEntry::kind).toList();
  }

  @Nested
  class ResumeCompensation {

    @Test
    @DisplayName("RC1/RC3: resume compensates B then A, C not again, round 1, SagaCompensated r1")
    void resumeSucceeds() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = compensationFailed(fixture);
      fixture.journal().clearFailures();

      RepairResult result = fixture.operations().resumeCompensation(sagaId, ALICE);

      assertThat(result).isInstanceOf(RepairResult.Applied.class);
      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATED);
      assertThat(ended.compensationRound()).isEqualTo(1);
      assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoB", "undoA");
      assertThat(outcomes(fixture, SagaCompensated.class))
          .extracting(SagaOutcome::eventId)
          .containsExactly(sagaId + ":COMPENSATED:r1");
      assertThat(fixture.committed(sagaId)).isEqualTo(ended);
    }

    @Test
    @DisplayName("RC2: a resume that fails again ends COMPENSATION_FAILED round 1, a distinct id")
    void resumeFailsAgain() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = compensationFailed(fixture);

      RepairResult result = fixture.operations().resumeCompensation(sagaId, ALICE);

      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
      assertThat(ended.compensationRound()).isEqualTo(1);
      assertThat(ended.steps().get(UnwindSaga.B).compensation().status())
          .isEqualTo(CompensationStatus.FAILED);
      assertThat(outcomes(fixture, SagaFailed.class))
          .extracting(SagaOutcome::eventId)
          .containsExactly(sagaId + ":COMPENSATION_FAILED", sagaId + ":COMPENSATION_FAILED:r1");
    }

    @Test
    @DisplayName("RC5: a stalled COMPENSATING instance resumes, round unchanged")
    void stalledCompensatingResumes() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = crashAfterFirstCompensation(fixture);

      RepairResult result = fixture.restart().operations().resumeCompensation(sagaId, ALICE);

      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATED);
      assertThat(ended.compensationRound()).isZero();
      assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
      assertThat(kinds(fixture, sagaId)).contains(SagaLifecycleEventKind.COMPENSATION_RESUMED);
    }

    @Test
    @DisplayName("RC6/R3: a COMPLETED instance is WRONG_STATUS, nothing written")
    void completedIsWrongStatus() {
      UnwindFixture fixture = UnwindFixture.withEveryCompensation();
      String sagaId = fixture.start("o-1");
      fixture.receive(new Finished("o-1"));
      List<SagaLifecycleEntry> before = fixture.operations().history(sagaId);

      RepairResult result = fixture.operations().resumeCompensation(sagaId, ALICE);

      assertThat(result)
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.COMPLETED));
      assertThat(fixture.operations().history(sagaId)).isEqualTo(before);
    }

    @Test
    @DisplayName("R2: an unknown id is NOT_FOUND")
    void unknownIsNotFound() {
      UnwindFixture fixture = UnwindFixture.withEveryCompensation();

      assertThat(fixture.operations().resumeCompensation("nope", ALICE))
          .isEqualTo(new RepairResult.NotApplied(RepairRejection.NOT_FOUND, null));
      assertThat(fixture.operations().compensate("nope", ALICE))
          .isEqualTo(new RepairResult.NotApplied(RepairRejection.NOT_FOUND, null));
      assertThat(fixture.operations().markResolved("nope", ALICE))
          .isEqualTo(new RepairResult.NotApplied(RepairRejection.NOT_FOUND, null));
    }

    @Test
    @DisplayName("RC7/R5: history shows COMPENSATION_RESUMED with the operator, then the unwinding")
    void historyAfterResume() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = compensationFailed(fixture);
      fixture.journal().clearFailures();

      fixture.operations().resumeCompensation(sagaId, ALICE);

      List<SagaLifecycleEntry> history = fixture.operations().history(sagaId);
      List<SagaLifecycleEventKind> kinds = history.stream().map(SagaLifecycleEntry::kind).toList();
      int failed = kinds.indexOf(SagaLifecycleEventKind.COMPENSATION_FAILED);
      int resumed = kinds.indexOf(SagaLifecycleEventKind.COMPENSATION_RESUMED);
      assertThat(failed).isNotNegative().isLessThan(resumed);
      assertThat(history.get(resumed).operator()).isEqualTo(ALICE);
      assertThat(kinds.subList(resumed, kinds.size()))
          .containsSubsequence(
              SagaLifecycleEventKind.COMPENSATION_RESUMED,
              SagaLifecycleEventKind.STEP_COMPENSATED,
              SagaLifecycleEventKind.STEP_COMPENSATED,
              SagaLifecycleEventKind.COMPENSATED);
    }

    @Test
    @DisplayName("R5: a storage failure during the resume write leaves neither change nor entry")
    void storageFailureRollsBack() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = compensationFailed(fixture);
      SagaInstance before = fixture.committed(sagaId);
      List<SagaLifecycleEntry> history = fixture.operations().history(sagaId);
      CompensationFaults faults = fixture.withFaults();
      faults.crashOnSave(1);

      Throwable thrown =
          catchThrowable(() -> fixture.operations().resumeCompensation(sagaId, ALICE));

      assertThat(thrown).isInstanceOf(CompensationFaults.Crash.class);
      fixture.restart();
      assertThat(fixture.committed(sagaId)).isEqualTo(before);
      assertThat(fixture.operations().history(sagaId)).isEqualTo(history);
    }
  }

  /** Aborts with a crash at the lock of B's compensation, after C's committed. */
  static String crashAfterFirstCompensation(UnwindFixture fixture) {
    CompensationFaults faults = fixture.withFaults();
    faults.crashOnLock(3);
    assertThat(catchThrowable(() -> fixture.receive(new Aborted("o-1")))).isNotNull();
    String sagaId = "saga-1";
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATING);
    return sagaId;
  }

  @Nested
  class Compensate {

    @Test
    @DisplayName(
        "CP1/CP5: RUNNING with committed steps compensates newest first, trigger by operator")
    void runningCompensates() {
      UnwindFixture fixture = UnwindFixture.withEveryCompensation();
      String sagaId = fixture.start("o-1");
      fixture.receive(new BDone("o-1"));

      RepairResult result = fixture.operations().compensate(sagaId, ALICE);

      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATED);
      assertThat(compensations(fixture)).containsExactly("undoB", "undoA");
      assertThat(ended.compensationTrigger().kind())
          .isEqualTo(CompensationTriggerKind.OPERATOR_REQUEST);
      assertThat(ended.compensationTrigger().operatorRequest()).isEqualTo(ALICE);
      assertThat(ended.compensationTrigger().stepName()).isEqualTo(UnwindSaga.B);
      SagaCompensated outcome = outcomes(fixture, SagaCompensated.class).get(0);
      assertThat(outcome.trigger().kind()).isEqualTo(CompensationTriggerKind.OPERATOR_REQUEST);
      assertThat(outcome.trigger().operatorRequest()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("CP2: a FAILED instance compensates, the trigger carries the recorded failure")
    void failedCompensates() {
      UnwindFixture fixture = UnwindFixture.withEveryCompensation();
      String sagaId = fixture.start("o-1");
      fixture.journal().failOn("b", new IllegalStateException("boom"));
      fixture.receive(new BDone("o-1"));
      SagaInstance failed = fixture.committed(sagaId);
      assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);

      RepairResult result = fixture.operations().compensate(sagaId, ALICE);

      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATED);
      assertThat(ended.failure()).isNull();
      assertThat(ended.compensationTrigger().failure()).isEqualTo(failed.failure());
      assertThat(compensations(fixture)).containsExactly("undoA");
    }

    @Test
    @DisplayName("CP6: COMPENSATING, COMPLETED and COMPENSATED are WRONG_STATUS")
    void wrongStatuses() {
      UnwindFixture completed = UnwindFixture.withEveryCompensation();
      String done = completed.start("o-1");
      completed.receive(new Finished("o-1"));
      UnwindFixture compensated = UnwindFixture.withEveryCompensation();
      String undone = compensated.start("o-1");
      compensated.receive(new Aborted("o-1"));
      UnwindFixture compensating = threeCommitted();
      String stalled = crashAfterFirstCompensation(compensating);
      compensating.restart();

      assertThat(completed.operations().compensate(done, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.COMPLETED));
      assertThat(compensated.operations().compensate(undone, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.COMPENSATED));
      assertThat(compensating.operations().compensate(stalled, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.COMPENSATING));
    }
  }

  @Nested
  class MarkResolved {

    @Test
    @DisplayName(
        "MR1/R4: resolving COMPENSATION_FAILED keeps the status, writes RESOLVED, no outcome")
    void resolvesCompensationFailed() {
      UnwindFixture fixture = threeCommitted();
      String sagaId = compensationFailed(fixture);
      int outcomesBefore = fixture.outbox().outcomes().size();
      int compensationsBefore = compensations(fixture).size();

      RepairResult result = fixture.operations().markResolved(sagaId, ALICE);

      SagaInstance ended = ((RepairResult.Applied) result).instance();
      assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
      assertThat(ended.resolution().operator()).isEqualTo("alice");
      assertThat(ended.resolution().reason()).isEqualTo(ALICE.reason());
      assertThat(kinds(fixture, sagaId)).endsWith(SagaLifecycleEventKind.RESOLVED);
      assertThat(fixture.outbox().outcomes()).hasSize(outcomesBefore);
      assertThat(compensations(fixture)).hasSize(compensationsBefore);

      assertThat(fixture.operations().markResolved(sagaId, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(
                  RepairRejection.ALREADY_RESOLVED, SagaStatus.COMPENSATION_FAILED));
      assertThat(fixture.operations().resumeCompensation(sagaId, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(
                  RepairRejection.ALREADY_RESOLVED, SagaStatus.COMPENSATION_FAILED));
    }

    @Test
    @DisplayName("MR2/R4: a FAILED instance resolves; compensate then reports ALREADY_RESOLVED")
    void resolvesFailed() {
      UnwindFixture fixture = UnwindFixture.withEveryCompensation();
      String sagaId = fixture.start("o-1");
      fixture.journal().failOn("b", new IllegalStateException("boom"));
      fixture.receive(new BDone("o-1"));

      RepairResult result = fixture.operations().markResolved(sagaId, ALICE);

      assertThat(((RepairResult.Applied) result).instance().status()).isEqualTo(SagaStatus.FAILED);
      assertThat(fixture.operations().compensate(sagaId, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.ALREADY_RESOLVED, SagaStatus.FAILED));
      assertThat(fixture.operations().count(java.util.Set.of()).failedUnresolved()).isZero();
    }

    @Test
    @DisplayName("MR3: RUNNING and COMPENSATING are WRONG_STATUS")
    void wrongStatuses() {
      UnwindFixture running = UnwindFixture.withEveryCompensation();
      String sagaId = running.start("o-1");
      UnwindFixture compensating = threeCommitted();
      String stalled = crashAfterFirstCompensation(compensating);
      compensating.restart();

      assertThat(running.operations().markResolved(sagaId, ALICE))
          .isEqualTo(new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.RUNNING));
      assertThat(compensating.operations().markResolved(stalled, ALICE))
          .isEqualTo(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.COMPENSATING));
    }
  }
}
