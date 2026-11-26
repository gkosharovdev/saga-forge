package io.github.gkosharovdev.sagaforge.core.operations;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which saga instances a listing returns, and which page of them. Instances match when every given
 * criterion holds; an empty set or a {@code null} bound means "any". Results are ordered by ({@code
 * lastModifiedAt}, {@code sagaId}) ascending and paged by {@link PageCursor}.
 *
 * <p>Start from {@link #all()} and narrow it with the withers, each of which returns a new query.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a set is {@code null} or
 * holds {@code null} or a blank type, if {@code limit} is outside 1 to {@link #MAX_LIMIT}, or if
 * {@code modifiedAfter} is not before {@code modifiedBefore}.
 *
 * @param statuses the statuses to match; empty for any
 * @param sagaTypes the saga type names to match; empty for any
 * @param modifiedBefore only instances last modified strictly before; {@code null} for no bound
 * @param modifiedAfter only instances last modified strictly after; {@code null} for no bound
 * @param limit the page size, 1 to {@link #MAX_LIMIT}
 * @param after where the page starts; {@code null} for the first page
 */
public record SagaInstanceQuery(
    Set<SagaStatus> statuses,
    Set<String> sagaTypes,
    Instant modifiedBefore,
    Instant modifiedAfter,
    int limit,
    PageCursor after) {

  /** The page size of {@link #all()}. */
  public static final int DEFAULT_LIMIT = 50;

  /** The largest accepted page size. */
  public static final int MAX_LIMIT = 500;

  public SagaInstanceQuery {
    Checks.nonNull(statuses, "statuses");
    Checks.nonNull(sagaTypes, "sagaTypes");
    for (SagaStatus status : statuses) {
      Checks.nonNull(status, "statuses element");
    }
    for (String type : sagaTypes) {
      Checks.nonBlank(type, "sagaTypes element", Integer.MAX_VALUE);
    }
    statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
    sagaTypes = Set.copyOf(sagaTypes);
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_LIMIT + ", but is " + limit);
    }
    if (modifiedBefore != null
        && modifiedAfter != null
        && !modifiedAfter.isBefore(modifiedBefore)) {
      throw new IllegalArgumentException(
          "modifiedAfter " + modifiedAfter + " must be before modifiedBefore " + modifiedBefore);
    }
  }

  /**
   * @return every instance, first page, {@link #DEFAULT_LIMIT} per page
   */
  public static SagaInstanceQuery all() {
    return new SagaInstanceQuery(Set.of(), Set.of(), null, null, DEFAULT_LIMIT, null);
  }

  /**
   * @return this query matching only {@code matching} statuses
   */
  public SagaInstanceQuery withStatuses(SagaStatus... matching) {
    return new SagaInstanceQuery(
        Set.of(matching), sagaTypes, modifiedBefore, modifiedAfter, limit, after);
  }

  /**
   * @return this query matching only the saga types named {@code matching}
   */
  public SagaInstanceQuery withSagaTypes(String... matching) {
    return new SagaInstanceQuery(
        statuses, Set.copyOf(Arrays.asList(matching)), modifiedBefore, modifiedAfter, limit, after);
  }

  /**
   * @return this query matching only instances last modified strictly before {@code bound}
   */
  public SagaInstanceQuery modifiedBefore(Instant bound) {
    return new SagaInstanceQuery(statuses, sagaTypes, bound, modifiedAfter, limit, after);
  }

  /**
   * @return this query matching only instances last modified strictly after {@code bound}
   */
  public SagaInstanceQuery modifiedAfter(Instant bound) {
    return new SagaInstanceQuery(statuses, sagaTypes, modifiedBefore, bound, limit, after);
  }

  /**
   * @return this query with page size {@code pageSize}
   */
  public SagaInstanceQuery limit(int pageSize) {
    return new SagaInstanceQuery(
        statuses, sagaTypes, modifiedBefore, modifiedAfter, pageSize, after);
  }

  /**
   * @return this query starting after {@code cursor}; {@code null} for the first page
   */
  public SagaInstanceQuery after(PageCursor cursor) {
    return new SagaInstanceQuery(statuses, sagaTypes, modifiedBefore, modifiedAfter, limit, cursor);
  }

  /**
   * @return whether an instance with these values matches the filter and lies after the cursor; the
   *     reference rule storage implementations follow
   */
  public boolean matches(
      SagaStatus status, String sagaType, Instant lastModifiedAt, String sagaId) {
    return (statuses.isEmpty() || statuses.contains(status))
        && (sagaTypes.isEmpty() || sagaTypes.contains(sagaType))
        && (modifiedBefore == null || lastModifiedAt.isBefore(modifiedBefore))
        && (modifiedAfter == null || lastModifiedAt.isAfter(modifiedAfter))
        && (after == null || isAfterCursor(lastModifiedAt, sagaId));
  }

  private boolean isAfterCursor(Instant lastModifiedAt, String sagaId) {
    int byTime = lastModifiedAt.compareTo(after.lastModifiedAt());
    return byTime > 0 || (byTime == 0 && sagaId.compareTo(after.sagaId()) > 0);
  }
}
