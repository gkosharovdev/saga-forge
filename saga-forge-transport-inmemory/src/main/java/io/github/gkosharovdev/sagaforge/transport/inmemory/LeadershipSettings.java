package io.github.gkosharovdev.sagaforge.transport.inmemory;

import java.time.Duration;

/**
 * Leader election timings of the transport's relay ({@code specs/013-active-standby-relay} FR-004,
 * FR-005, research R4). The same three knobs as Kubernetes' leader election (lease duration, renew
 * deadline, retry period), scaled for a database round trip.
 *
 * <p>After a crash of the active instance a standby takes over within {@code leaseDuration +
 * checkInterval}; after a graceful stop within {@code checkInterval}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a duration is not
 * positive or if {@code renewInterval} or {@code checkInterval} is not shorter than {@code
 * leaseDuration}.
 *
 * @param leaseDuration how long a leadership lasts without renewal
 * @param renewInterval how often the active relay renews its leadership
 * @param checkInterval how often a standby relay tries to take over
 */
public record LeadershipSettings(
    Duration leaseDuration, Duration renewInterval, Duration checkInterval) {

  public LeadershipSettings {
    positive(leaseDuration, "leaseDuration");
    positive(renewInterval, "renewInterval");
    positive(checkInterval, "checkInterval");
    shorter(renewInterval, "renewInterval", leaseDuration);
    shorter(checkInterval, "checkInterval", leaseDuration);
  }

  /**
   * @return a 30 s lease, renewed every 10 s, checked by standby relays every 5 s
   */
  public static LeadershipSettings defaults() {
    return new LeadershipSettings(
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5));
  }

  private static void positive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be a positive duration, but is " + value);
    }
  }

  private static void shorter(Duration value, String name, Duration leaseDuration) {
    if (value.compareTo(leaseDuration) >= 0) {
      throw new IllegalArgumentException(
          name
              + " ("
              + value
              + ") must be shorter than the leadership leaseDuration ("
              + leaseDuration
              + ")");
    }
  }
}
