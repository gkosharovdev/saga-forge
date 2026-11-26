package io.github.gkosharovdev.sagaforge.core.operations;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One page of a listing, in ({@code lastModifiedAt}, {@code sagaId}) order, and where the next one
 * starts.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component or an element
 * is {@code null}.
 *
 * @param instances the instances of this page, copied
 * @param next where the next page starts; present if and only if more instances matched
 */
public record SagaInstancePage(List<SagaInstance> instances, Optional<PageCursor> next) {

  /** The listing order. */
  public static final Comparator<SagaInstance> ORDER =
      Comparator.comparing(SagaInstance::lastModifiedAt).thenComparing(SagaInstance::sagaId);

  public SagaInstancePage {
    Checks.nonNull(instances, "instances");
    Checks.nonNull(next, "next");
    for (SagaInstance instance : instances) {
      Checks.nonNull(instance, "instances element");
    }
    instances = List.copyOf(instances);
  }

  /**
   * Builds a page from up to {@code limit + 1} matching instances in {@link #ORDER}: the first
   * {@code limit} are the page, and the extra one, if present, says that more exist.
   */
  public static SagaInstancePage of(List<SagaInstance> probe, int limit) {
    Checks.nonNull(probe, "probe");
    if (probe.size() <= limit) {
      return new SagaInstancePage(probe, Optional.empty());
    }
    List<SagaInstance> page = probe.subList(0, limit);
    SagaInstance last = page.get(limit - 1);
    return new SagaInstancePage(
        page, Optional.of(new PageCursor(last.lastModifiedAt(), last.sagaId())));
  }
}
