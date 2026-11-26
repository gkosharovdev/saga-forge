package io.github.gkosharovdev.sagaforge.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.PageCursor;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstancePage;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaResolution;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The query, count and history contract of {@link StorageProvider} ({@code
 * specs/015-saga-operations/contracts/storage-query-spi.md}, cases Q1 to Q10). Implementations
 * supply a fresh, empty store and a way to run work in the store's own transaction.
 *
 * <p>Times are whole milliseconds, which every storage keeps exactly.
 */
public abstract class StorageProviderQueryContract {

  /** The base time of every instance in this contract. */
  protected static final Instant T0 = Instant.parse("2026-11-26T09:00:00Z");

  private static final String TYPE_A = "com.example.SagaA";
  private static final String TYPE_B = "com.example.SagaB";
  private static final OperatorRequest REQUEST = new OperatorRequest("alice", "checked by hand");

  private StorageProvider storage;

  /**
   * @return a new store with no instances; called before every case
   */
  protected abstract StorageProvider newStorage();

  /**
   * Runs {@code work} in a transaction of {@code storage}'s own and commits it.
   *
   * @param storage the store {@link #newStorage()} returned
   * @param work the work to run
   */
  protected abstract void inTransaction(StorageProvider storage, Runnable work);

  @BeforeEach
  void freshStorage() {
    storage = newStorage();
  }

  private void save(SagaInstance instance, SagaLifecycleEntry... entries) {
    inTransaction(storage, () -> storage.save(instance, List.of(entries)));
  }

  private static SagaInstance running(String sagaId, String type, Instant modified) {
    return new SagaInstance(
        sagaId,
        type,
        Map.of(),
        Map.of("orderId", sagaId),
        SagaStatus.RUNNING,
        T0,
        modified,
        Map.of(),
        null,
        null);
  }

  private static SagaInstance failed(String sagaId, String type, Instant modified) {
    return new SagaInstance(
        sagaId,
        type,
        Map.of(),
        Map.of("orderId", sagaId),
        SagaStatus.FAILED,
        T0,
        modified,
        Map.of(),
        null,
        new SagaFailure("pay", "java.lang.IllegalStateException", "declined", modified));
  }

  private static SagaInstance completed(String sagaId, String type, Instant modified) {
    return new SagaInstance(
        sagaId,
        type,
        Map.of(),
        Map.of("orderId", sagaId),
        SagaStatus.COMPLETED,
        T0,
        modified,
        Map.of(),
        "done",
        null);
  }

  private List<SagaInstance> readAll(SagaInstanceQuery first) {
    List<SagaInstance> seen = new ArrayList<>();
    SagaInstancePage page = storage.find(first);
    seen.addAll(page.instances());
    while (page.next().isPresent()) {
      page = storage.find(first.after(page.next().get()));
      seen.addAll(page.instances());
    }
    return seen;
  }

  @Test
  void q1EmptyStoreReturnsAnEmptyPage() {
    SagaInstancePage page = storage.find(SagaInstanceQuery.all());

    assertThat(page.instances()).isEmpty();
    assertThat(page.next()).isEmpty();
  }

  @Test
  void q2PagesFollowTheOrderAndTheLastHasNoNext() {
    for (int i = 6; i >= 0; i--) {
      save(running("saga-" + i, TYPE_A, T0.plusSeconds(i)));
    }
    SagaInstanceQuery query = SagaInstanceQuery.all().limit(3);

    SagaInstancePage first = storage.find(query);
    SagaInstancePage second = storage.find(query.after(first.next().orElseThrow()));
    SagaInstancePage third = storage.find(query.after(second.next().orElseThrow()));

    assertThat(first.instances())
        .extracting(SagaInstance::sagaId)
        .containsExactly("saga-0", "saga-1", "saga-2");
    assertThat(second.instances())
        .extracting(SagaInstance::sagaId)
        .containsExactly("saga-3", "saga-4", "saga-5");
    assertThat(third.instances()).extracting(SagaInstance::sagaId).containsExactly("saga-6");
    assertThat(third.next()).isEmpty();
  }

  @Test
  void q3TiesAreBrokenBySagaId() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String id = "tie-" + (char) ('j' - i);
      ids.add(id);
      save(running(id, TYPE_A, T0));
    }

    List<SagaInstance> seen = readAll(SagaInstanceQuery.all().limit(4));

    assertThat(seen)
        .extracting(SagaInstance::sagaId)
        .containsExactlyElementsOf(ids.stream().sorted().toList());
  }

  @Test
  void q4StatusFilter() {
    save(running("r", TYPE_A, T0));
    save(failed("f", TYPE_A, T0.plusSeconds(1)));
    save(completed("c", TYPE_A, T0.plusSeconds(2)));

    SagaInstancePage page =
        storage.find(
            SagaInstanceQuery.all()
                .withStatuses(SagaStatus.FAILED, SagaStatus.COMPENSATION_FAILED));

    assertThat(page.instances()).extracting(SagaInstance::sagaId).containsExactly("f");
  }

  @Test
  void q5TypeFilter() {
    save(running("a1", TYPE_A, T0));
    save(running("b1", TYPE_B, T0.plusSeconds(1)));
    save(running("a2", TYPE_A, T0.plusSeconds(2)));

    SagaInstancePage page = storage.find(SagaInstanceQuery.all().withSagaTypes(TYPE_A));

    assertThat(page.instances()).extracting(SagaInstance::sagaId).containsExactly("a1", "a2");
  }

  @Test
  void q6TimeBoundsAreExclusive() {
    for (int i = 0; i < 5; i++) {
      save(running("saga-" + i, TYPE_A, T0.plusSeconds(i)));
    }

    SagaInstancePage page =
        storage.find(
            SagaInstanceQuery.all()
                .modifiedAfter(T0.plusSeconds(1))
                .modifiedBefore(T0.plusSeconds(4)));

    assertThat(page.instances())
        .extracting(SagaInstance::sagaId)
        .containsExactly("saga-2", "saga-3");
  }

  @Test
  void q7EveryUnchangedInstanceIsSeenExactlyOnceWhileOthersAreResaved() {
    for (long seed = 1; seed <= 5; seed++) {
      storage = newStorage();
      everyUnchangedInstanceSeenOnce(new Random(seed));
    }
  }

  private void everyUnchangedInstanceSeenOnce(Random random) {
    int count = 12 + random.nextInt(12);
    Map<String, Instant> modified = new HashMap<>();
    for (int i = 0; i < count; i++) {
      String id = "saga-" + i;
      Instant at = T0.plusMillis(random.nextInt(5) * 1_000L);
      modified.put(id, at);
      save(running(id, TYPE_A, at));
    }
    Set<String> changed = new HashSet<>();
    Map<String, Integer> seen = new HashMap<>();
    SagaInstanceQuery query = SagaInstanceQuery.all().limit(1 + random.nextInt(5));
    Optional<PageCursor> cursor = Optional.empty();
    Instant later = T0.plusSeconds(60);
    do {
      SagaInstancePage page = storage.find(cursor.map(query::after).orElse(query));
      page.instances().forEach(i -> seen.merge(i.sagaId(), 1, Integer::sum));
      cursor = page.next();
      if (random.nextBoolean()) {
        String id = "saga-" + random.nextInt(count);
        later = later.plusSeconds(1);
        changed.add(id);
        save(running(id, TYPE_A, later));
      }
    } while (cursor.isPresent());

    for (String id : modified.keySet()) {
      if (!changed.contains(id)) {
        assertThat(seen.get(id)).as(id).isEqualTo(1);
      }
    }
  }

  @Test
  void q8CountsHaveEveryStatusAndExcludeResolvedFromUnresolved() {
    save(running("r", TYPE_A, T0));
    save(failed("f1", TYPE_A, T0));
    save(failed("f2", TYPE_A, T0).withResolution(SagaResolution.of(REQUEST, T0)));
    save(failed("fb", TYPE_B, T0));
    save(completed("c", TYPE_B, T0));

    InstanceCounts all = storage.countByStatus(Set.of());
    InstanceCounts onlyA = storage.countByStatus(Set.of(TYPE_A));

    assertThat(all.byStatus()).containsOnlyKeys(SagaStatus.values());
    assertThat(all.get(SagaStatus.FAILED)).isEqualTo(3);
    assertThat(all.get(SagaStatus.COMPLETED)).isEqualTo(1);
    assertThat(all.get(SagaStatus.COMPENSATING)).isZero();
    assertThat(all.failedUnresolved()).isEqualTo(2);
    assertThat(onlyA.get(SagaStatus.FAILED)).isEqualTo(2);
    assertThat(onlyA.get(SagaStatus.COMPLETED)).isZero();
    assertThat(onlyA.failedUnresolved()).isEqualTo(1);
    assertThat(onlyA.compensationFailedUnresolved()).isZero();
  }

  @Test
  void q9HistoryInStoredOrderWithTheOperator() {
    SagaInstance instance = failed("h", TYPE_A, T0);
    SagaLifecycleEntry failedEntry =
        new SagaLifecycleEntry(
            "h", SagaLifecycleEventKind.FAILED, null, List.of(), null, instance.failure(), T0);
    SagaLifecycleEntry resolved =
        new SagaLifecycleEntry(
            "h",
            SagaLifecycleEventKind.RESOLVED,
            null,
            List.of(),
            null,
            null,
            T0.plusSeconds(1),
            null,
            REQUEST);
    save(running("h", TYPE_A, T0));
    save(instance, failedEntry);
    save(instance.withResolution(SagaResolution.of(REQUEST, T0.plusSeconds(1))), resolved);

    assertThat(storage.history("h")).containsExactly(failedEntry, resolved);
    assertThat(storage.history("h").get(1).operator()).isEqualTo(REQUEST);
    assertThat(storage.history("unknown")).isEmpty();
  }

  @Test
  void q10NewInstanceFieldsRoundTrip() {
    SagaInstance resolved =
        failed("resolved", TYPE_A, T0)
            .withResolution(SagaResolution.of(REQUEST, T0))
            .withCompensationRound(2);
    SagaInstance byOperator =
        new SagaInstance(
            "by-operator",
            TYPE_A,
            Map.of(),
            Map.of("orderId", "by-operator"),
            SagaStatus.COMPENSATING,
            T0,
            T0.plusSeconds(1),
            Map.of(),
            null,
            null,
            null,
            CompensationTrigger.byOperator(REQUEST, "pay", null, T0.plusSeconds(1)));
    save(resolved);
    save(byOperator);

    assertThat(storage.findById("resolved")).contains(resolved);
    assertThat(storage.findById("by-operator")).contains(byOperator);
    assertThat(storage.find(SagaInstanceQuery.all()).instances())
        .containsExactly(resolved, byOperator);
  }
}
