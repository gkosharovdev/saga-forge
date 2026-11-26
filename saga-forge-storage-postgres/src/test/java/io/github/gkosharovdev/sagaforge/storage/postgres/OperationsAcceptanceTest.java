package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstancePage;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.RecoverySettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Order;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Operations with two application instances on one PostgreSQL database ({@code
 * specs/015-saga-operations} tasks.md T064, SW5 and the keyset listing): both recover the same
 * stalled instances at once, and a paged listing sees each instance exactly once.
 *
 * <p>Counts are reduced from the spec's scale (40 stalled instances, 60 listed) to keep the suite
 * fast; ST4 (a stranded start recovered by a redelivered start event) is covered over in-memory
 * plug-ins only, since forcing the crash between the creation write and the first unit of work
 * needs a fault the database cannot inject.
 */
@Tag("container")
class OperationsAcceptanceTest {

  private static final Duration STALL_AGE = Duration.ofSeconds(1);
  private static final Map<String, Integer> UNDONE = new ConcurrentHashMap<>();

  /** Starts {@link TallySaga}. */
  @DomainEvent(aggregate = Order.class)
  public record TallyOpened(@AggregateIdentifier String orderId) {}

  /** {@link TallySaga}'s middle step. */
  @DomainEvent(aggregate = Order.class)
  public record TallyBooked(@AggregateIdentifier String orderId) {}

  /** Ends {@link TallySaga}. */
  @DomainEvent(aggregate = Order.class)
  public record TallyClosed(@AggregateIdentifier String orderId) {}

  /** Two committed steps, each with a compensation that counts its runs. */
  @Saga
  public static final class TallySaga {

    @SagaId private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void opened(TallyOpened event) {}

    @SagaStep(associationProperty = "orderId")
    public void booked(TallyBooked event) {}

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void closed(TallyClosed event) {}

    @SagaCompensation(forStep = "opened")
    public void undoOpened() {
      UNDONE.merge(sagaId + ":opened", 1, Integer::sum);
    }

    @SagaCompensation(forStep = "booked")
    public void undoBooked() {
      UNDONE.merge(sagaId + ":booked", 1, Integer::sum);
    }
  }

  private final List<Replica> instances = new ArrayList<>();

  @BeforeEach
  void resetDatabase() {
    PostgresTestSupport.reset();
    UNDONE.clear();
  }

  @AfterEach
  void stopAll() {
    instances.forEach(Replica::stop);
  }

  private Replica instance() {
    Replica instance =
        new Replica(
            TransportHarness.FAST,
            Map.of(),
            RecoverySettings.defaults().withStallAge(STALL_AGE),
            TallySaga.class);
    instances.add(instance);
    return instance;
  }

  /** {@code count} RUNNING instances with both steps committed, through {@code replica}. */
  private static List<String> running(Replica replica, int count) {
    for (int i = 0; i < count; i++) {
      replica.receive(new TallyOpened("o-" + i));
      replica.receive(new TallyBooked("o-" + i));
    }
    List<String> ids = all(replica.operations(), SagaInstanceQuery.all().limit(17));
    assertThat(ids).hasSize(count);
    return ids;
  }

  /** Rewrites {@code sagaId} as COMPENSATING an hour ago with nothing compensated: a stall. */
  private static void stall(SagaOperations operations, String sagaId) {
    SagaInstance current = operations.find(sagaId).orElseThrow();
    Instant past = Instant.now().minus(Duration.ofHours(1));
    Map<String, StepRecord> steps = new HashMap<>();
    current
        .steps()
        .forEach(
            (name, step) ->
                steps.put(
                    name,
                    step.status() == StepStatus.COMPLETED
                        ? step.withCompensation(StepCompensation.notAttempted())
                        : step));
    CompensationTrigger trigger =
        CompensationTrigger.failed(
            new SagaFailure("closed", "test.Refused", null, past, FailureClassification.FINAL, 1),
            past);
    new PostgresStorageProvider(
            PostgresTestSupport.newDataSource(),
            new JdbcTransactionBoundary(PostgresTestSupport.newDataSource()))
        .save(
            new SagaInstance(
                current.sagaId(),
                current.sagaType(),
                Map.of(),
                current.associations(),
                SagaStatus.COMPENSATING,
                current.createdAt(),
                past,
                steps,
                null,
                null,
                null,
                trigger),
            List.of());
  }

  /** Every saga id {@code query} matches, page by page. */
  private static List<String> all(SagaOperations operations, SagaInstanceQuery query) {
    List<String> ids = new ArrayList<>();
    SagaInstanceQuery page = query;
    while (true) {
      SagaInstancePage found = operations.list(page);
      found.instances().forEach(instance -> ids.add(instance.sagaId()));
      if (found.next().isEmpty()) {
        return ids;
      }
      page = page.after(found.next().get());
    }
  }

  @Test
  @DisplayName("SW5: two instances recover the same stalled sagas at once; each undo runs once")
  void concurrentRecovery() throws Exception {
    Replica a = instance();
    Replica b = instance();
    List<String> sagaIds = running(a, 40);
    sagaIds.forEach(sagaId -> stall(a.operations(), sagaId));

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<RecoveryReport> reports = new ArrayList<>();
    try {
      List<Future<RecoveryReport>> results = new ArrayList<>();
      for (Replica replica : List.of(a, b)) {
        Callable<RecoveryReport> recover =
            () -> {
              go.await();
              return replica.operations().recover();
            };
        results.add(pool.submit(recover));
      }
      go.countDown();
      for (Future<RecoveryReport> result : results) {
        reports.add(result.get());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(reports.stream().mapToInt(RecoveryReport::compensationsResumed).sum())
        .isEqualTo(sagaIds.size());
    assertThat(UNDONE)
        .hasSize(sagaIds.size() * 2)
        .allSatisfy((key, runs) -> assertThat(runs).isOne());
    assertThat(a.operations().count(Set.of()).get(SagaStatus.COMPENSATED))
        .isEqualTo(sagaIds.size());
    assertThat(b.operations().recover()).isEqualTo(RecoveryReport.none());
  }

  @Test
  @DisplayName("keyset listing: pages from either instance see each saga exactly once")
  void pagedListing() {
    Replica a = instance();
    Replica b = instance();
    List<String> sagaIds = running(a, 60);
    for (int i = 0; i < 10; i++) {
      a.receive(new TallyClosed("o-" + i));
    }

    List<String> fromB = all(b.operations(), SagaInstanceQuery.all().limit(7));
    List<String> completed =
        all(a.operations(), SagaInstanceQuery.all().withStatuses(SagaStatus.COMPLETED).limit(3));

    assertThat(fromB).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(sagaIds);
    assertThat(completed).doesNotHaveDuplicates().hasSize(10);
    assertThat(b.operations().count(Set.of()).get(SagaStatus.RUNNING)).isEqualTo(50);
  }
}
