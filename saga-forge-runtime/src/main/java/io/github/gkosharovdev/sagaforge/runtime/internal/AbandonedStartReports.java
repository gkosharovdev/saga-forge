package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * When this process last reported each abandoned start ({@code specs/015-saga-operations} research
 * R9): at most once per abandonment age per instance. Kept in memory only, so a restart reports an
 * instance again, once (G-015-07).
 *
 * <p>Thread-safe.
 */
final class AbandonedStartReports {

  private final ConcurrentMap<String, Instant> reportedAt = new ConcurrentHashMap<>();

  /**
   * Records a report of {@code sagaId} at {@code now} unless one was recorded less than {@code
   * every} ago.
   *
   * @return {@code true} if the caller should report it now
   */
  boolean shouldReport(String sagaId, Instant now, Duration every) {
    boolean[] report = {false};
    reportedAt.compute(
        sagaId,
        (id, last) -> {
          if (last == null || !now.isBefore(last.plus(every))) {
            report[0] = true;
            return now;
          }
          return last;
        });
    return report[0];
  }

  /** Forgets every instance not in {@code stillAbandoned}: it left {@code CREATED}. */
  void retainOnly(Set<String> stillAbandoned) {
    reportedAt.keySet().retainAll(stillAbandoned);
  }

  /** The number of remembered instances. */
  int size() {
    return reportedAt.size();
  }
}
