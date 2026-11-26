package io.github.gkosharovdev.sagaforge.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OperationsRecordsTest {

  private static final Instant AT = Instant.parse("2026-11-26T10:15:30.123456789Z");

  private static SagaInstance instance(String sagaId, Instant lastModifiedAt) {
    return new SagaInstance(
        sagaId,
        "com.example.OrderSaga",
        Map.of(),
        Map.of(),
        SagaStatus.RUNNING,
        AT,
        lastModifiedAt,
        Map.of(),
        null,
        null);
  }

  @Nested
  @DisplayName("OperatorRequest and SagaResolution")
  class OperatorRequests {

    @Test
    void acceptsOperatorAndReasonAtTheirLimits() {
      var request = new OperatorRequest("a".repeat(200), "r".repeat(2_000));

      assertThat(request.operator()).hasSize(200);
      assertThat(request.reason()).hasSize(2_000);
    }

    @Test
    void rejectsNullBlankAndOverlongValues() {
      assertThatThrownBy(() -> new OperatorRequest(null, "why"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OperatorRequest(" ", "why"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OperatorRequest("ops", ""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OperatorRequest("ops", null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OperatorRequest("a".repeat(201), "why"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OperatorRequest("ops", "r".repeat(2_001)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoveryRequestNamesTheRecoveryOperator() {
      assertThat(OperatorRequest.recovery("stranded start").operator())
          .isEqualTo("saga-forge-recovery")
          .isEqualTo(OperatorRequest.RECOVERY_OPERATOR);
    }

    @Test
    void resolutionCopiesTheRequest() {
      var resolution = SagaResolution.of(new OperatorRequest("alice", "refunded by hand"), AT);

      assertThat(resolution).isEqualTo(new SagaResolution("alice", "refunded by hand", AT));
    }

    @Test
    void resolutionRejectsMissingValues() {
      assertThatThrownBy(() -> SagaResolution.of(null, AT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new SagaResolution("alice", "why", null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new SagaResolution("", "why", AT))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("PageCursor")
  class PageCursors {

    @Test
    void tokenRoundTrips() {
      var cursor = new PageCursor(AT, "saga:with:colons.and.dots");

      assertThat(PageCursor.fromToken(cursor.token())).isEqualTo(cursor);
    }

    @Test
    void tokenRoundTripsBeforeTheEpoch() {
      var cursor = new PageCursor(Instant.parse("1960-01-01T00:00:00.5Z"), "saga-1");

      assertThat(PageCursor.fromToken(cursor.token())).isEqualTo(cursor);
    }

    @Test
    void tokenIsUrlSafe() {
      assertThat(new PageCursor(AT, "saga/?+=").token()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void rejectsMalformedTokens() {
      for (String plain : List.of("no-separators", "12:saga", "a.b:saga", "1.2", "1.2:")) {
        String token =
            java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PageCursor.fromToken(token))
            .as(plain)
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(() -> PageCursor.fromToken("***"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> PageCursor.fromToken(""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> PageCursor.fromToken(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingValues() {
      assertThatThrownBy(() -> new PageCursor(null, "saga-1"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new PageCursor(AT, " "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("SagaInstanceQuery")
  class Queries {

    @Test
    void allMatchesEverythingWithTheDefaultLimit() {
      var all = SagaInstanceQuery.all();

      assertThat(all.limit()).isEqualTo(50);
      assertThat(all.statuses()).isEmpty();
      assertThat(all.sagaTypes()).isEmpty();
      assertThat(all.matches(SagaStatus.COMPLETED, "any", AT, "saga-1")).isTrue();
    }

    @Test
    void limitBounds() {
      assertThat(SagaInstanceQuery.all().limit(1).limit()).isEqualTo(1);
      assertThat(SagaInstanceQuery.all().limit(500).limit()).isEqualTo(500);
      assertThatThrownBy(() -> SagaInstanceQuery.all().limit(0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> SagaInstanceQuery.all().limit(501))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modifiedAfterMustBeBeforeModifiedBefore() {
      var before = SagaInstanceQuery.all().modifiedBefore(AT);

      assertThat(before.modifiedAfter(AT.minusSeconds(1)).modifiedAfter())
          .isEqualTo(AT.minusSeconds(1));
      assertThatThrownBy(() -> before.modifiedAfter(AT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> before.modifiedAfter(AT.plusSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesItsSets() {
      Set<SagaStatus> statuses = new HashSet<>(Set.of(SagaStatus.FAILED));
      Set<String> types = new HashSet<>(Set.of("A"));
      var query = new SagaInstanceQuery(statuses, types, null, null, 10, null);
      statuses.add(SagaStatus.RUNNING);
      types.add("B");

      assertThat(query.statuses()).containsExactly(SagaStatus.FAILED);
      assertThat(query.sagaTypes()).containsExactly("A");
      assertThatThrownBy(() -> query.statuses().add(SagaStatus.CREATED))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullElements() {
      assertThatThrownBy(
              () ->
                  new SagaInstanceQuery(
                      new HashSet<>(Arrays.asList((SagaStatus) null)),
                      Set.of(),
                      null,
                      null,
                      1,
                      null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new SagaInstanceQuery(
                      Set.of(), new HashSet<>(Arrays.asList((String) null)), null, null, 1, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new SagaInstanceQuery(null, Set.of(), null, null, 1, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesAppliesEveryFilterWithExclusiveBounds() {
      var query =
          SagaInstanceQuery.all()
              .withStatuses(SagaStatus.FAILED, SagaStatus.COMPENSATION_FAILED)
              .withSagaTypes("A")
              .modifiedAfter(AT.minusSeconds(10))
              .modifiedBefore(AT);

      assertThat(query.matches(SagaStatus.FAILED, "A", AT.minusSeconds(5), "s")).isTrue();
      assertThat(query.matches(SagaStatus.RUNNING, "A", AT.minusSeconds(5), "s")).isFalse();
      assertThat(query.matches(SagaStatus.FAILED, "B", AT.minusSeconds(5), "s")).isFalse();
      assertThat(query.matches(SagaStatus.FAILED, "A", AT, "s")).isFalse();
      assertThat(query.matches(SagaStatus.FAILED, "A", AT.minusSeconds(10), "s")).isFalse();
    }

    @Test
    void matchesOnlyWhatLiesAfterTheCursor() {
      var query = SagaInstanceQuery.all().after(new PageCursor(AT, "saga-5"));

      assertThat(query.matches(SagaStatus.RUNNING, "A", AT, "saga-5")).isFalse();
      assertThat(query.matches(SagaStatus.RUNNING, "A", AT, "saga-4")).isFalse();
      assertThat(query.matches(SagaStatus.RUNNING, "A", AT, "saga-6")).isTrue();
      assertThat(query.matches(SagaStatus.RUNNING, "A", AT.minusNanos(1), "saga-9")).isFalse();
      assertThat(query.matches(SagaStatus.RUNNING, "A", AT.plusNanos(1), "saga-0")).isTrue();
    }
  }

  @Nested
  @DisplayName("SagaInstancePage")
  class Pages {

    @Test
    void probeWithinTheLimitHasNoNextCursor() {
      var page = SagaInstancePage.of(List.of(instance("a", AT), instance("b", AT)), 2);

      assertThat(page.instances()).hasSize(2);
      assertThat(page.next()).isEmpty();
    }

    @Test
    void extraProbeRowYieldsACursorAtTheLastOfThePage() {
      var page =
          SagaInstancePage.of(
              List.of(instance("a", AT), instance("b", AT), instance("c", AT.plusSeconds(1))), 2);

      assertThat(page.instances()).extracting(SagaInstance::sagaId).containsExactly("a", "b");
      assertThat(page.next()).contains(new PageCursor(AT, "b"));
    }

    @Test
    void copiesAndRejectsNulls() {
      List<SagaInstance> rows = new ArrayList<>(List.of(instance("a", AT)));
      var page = SagaInstancePage.of(rows, 5);
      rows.add(instance("b", AT));

      assertThat(page.instances()).hasSize(1);
      assertThatThrownBy(
              () ->
                  new SagaInstancePage(
                      Arrays.asList((SagaInstance) null), java.util.Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderIsByLastModifiedThenSagaId() {
      List<SagaInstance> rows =
          new ArrayList<>(
              List.of(instance("b", AT), instance("a", AT.plusSeconds(1)), instance("a", AT)));
      rows.sort(SagaInstancePage.ORDER);

      assertThat(rows)
          .extracting(i -> i.sagaId() + "@" + i.lastModifiedAt().getEpochSecond())
          .containsExactly(
              "a@" + AT.getEpochSecond(),
              "b@" + AT.getEpochSecond(),
              "a@" + (AT.getEpochSecond() + 1));
    }
  }

  @Nested
  @DisplayName("InstanceCounts and RecoveryReport")
  class Counts {

    @Test
    void everyStatusKeyIsPresent() {
      var counts = new InstanceCounts(Map.of(SagaStatus.FAILED, 3L), 0, 2);

      assertThat(counts.byStatus()).containsOnlyKeys(SagaStatus.values());
      assertThat(counts.get(SagaStatus.FAILED)).isEqualTo(3);
      assertThat(counts.get(SagaStatus.RUNNING)).isZero();
      assertThat(InstanceCounts.none().byStatus().values()).containsOnly(0L);
    }

    @Test
    void copiesTheMap() {
      Map<SagaStatus, Long> source = new EnumMap<>(SagaStatus.class);
      source.put(SagaStatus.RUNNING, 1L);
      var counts = new InstanceCounts(source, 0, 0);
      source.put(SagaStatus.RUNNING, 7L);

      assertThat(counts.get(SagaStatus.RUNNING)).isEqualTo(1);
      assertThatThrownBy(() -> counts.byStatus().put(SagaStatus.RUNNING, 2L))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNegativeCountsAndUnresolvedAboveTheTotal() {
      assertThatThrownBy(() -> new InstanceCounts(Map.of(SagaStatus.FAILED, -1L), 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new InstanceCounts(Map.of(SagaStatus.FAILED, 1L), 0, 2))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new InstanceCounts(Map.of(), 1, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new InstanceCounts(null, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoveryReportRejectsNegativeNumbers() {
      assertThat(RecoveryReport.none()).isEqualTo(new RecoveryReport(0, 0, 0, 0));
      assertThatThrownBy(() -> new RecoveryReport(-1, 0, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new RecoveryReport(0, 0, 0, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("RepairResult")
  class RepairResults {

    @Test
    void appliedCarriesTheInstance() {
      RepairResult result = new RepairResult.Applied(instance("a", AT));

      assertThat(result.applied()).isTrue();
      assertThatThrownBy(() -> new RepairResult.Applied(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notFoundHasNoStatusAndEveryOtherRejectionHasOne() {
      assertThat(new RepairResult.NotApplied(RepairRejection.NOT_FOUND, null).applied()).isFalse();
      assertThat(
              new RepairResult.NotApplied(RepairRejection.WRONG_STATUS, SagaStatus.RUNNING)
                  .actualStatus())
          .isEqualTo(SagaStatus.RUNNING);
      assertThatThrownBy(
              () -> new RepairResult.NotApplied(RepairRejection.NOT_FOUND, SagaStatus.RUNNING))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new RepairResult.NotApplied(RepairRejection.ALREADY_RESOLVED, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new RepairResult.NotApplied(null, SagaStatus.FAILED))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
