package io.github.gkosharovdev.sagaforge.spring.transport;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code saga-forge.transport.*}: tuning of the in-memory transport ({@code
 * specs/007-in-memory-transport} contracts/spring-wiring.md). Defaults are {@link
 * TransportSettings#defaults()}; {@code saga-forge.transport.leadership.*} tunes the leader
 * election of the relays sharing one message store ({@code specs/013-active-standby-relay}).
 */
@ConfigurationProperties("saga-forge.transport")
public class TransportProperties {

  private static final TransportSettings DEFAULTS = TransportSettings.defaults();

  private int workers = DEFAULTS.workers();
  private int maxAttempts = DEFAULTS.maxAttempts();
  private Duration initialBackoff = DEFAULTS.initialBackoff();
  private Duration maxBackoff = DEFAULTS.maxBackoff();
  private Duration leaseDuration = DEFAULTS.leaseDuration();
  private Duration pollInterval = DEFAULTS.pollInterval();
  private int leaseBatchSize = DEFAULTS.leaseBatchSize();
  private Duration retention = DEFAULTS.retention();
  private Duration retentionSweepInterval = DEFAULTS.retentionSweepInterval();
  private final Leadership leadership = new Leadership();
  private boolean modulithInterop = true;

  /**
   * @return the settings these properties describe
   */
  public TransportSettings toSettings() {
    return new TransportSettings(
        workers,
        maxAttempts,
        initialBackoff,
        maxBackoff,
        leaseDuration,
        pollInterval,
        leaseBatchSize,
        retention,
        retentionSweepInterval,
        leadership.toSettings());
  }

  public int getWorkers() {
    return workers;
  }

  public void setWorkers(int workers) {
    this.workers = workers;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getInitialBackoff() {
    return initialBackoff;
  }

  public void setInitialBackoff(Duration initialBackoff) {
    this.initialBackoff = initialBackoff;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }

  public void setMaxBackoff(Duration maxBackoff) {
    this.maxBackoff = maxBackoff;
  }

  public Duration getLeaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public int getLeaseBatchSize() {
    return leaseBatchSize;
  }

  public void setLeaseBatchSize(int leaseBatchSize) {
    this.leaseBatchSize = leaseBatchSize;
  }

  public Duration getRetention() {
    return retention;
  }

  public void setRetention(Duration retention) {
    this.retention = retention;
  }

  public Duration getRetentionSweepInterval() {
    return retentionSweepInterval;
  }

  public void setRetentionSweepInterval(Duration value) {
    this.retentionSweepInterval = value;
  }

  public Leadership getLeadership() {
    return leadership;
  }

  public boolean isModulithInterop() {
    return modulithInterop;
  }

  public void setModulithInterop(boolean modulithInterop) {
    this.modulithInterop = modulithInterop;
  }

  /**
   * {@code saga-forge.transport.leadership.*}; defaults are {@link LeadershipSettings#defaults()}.
   */
  public static class Leadership {

    private static final LeadershipSettings DEFAULTS = LeadershipSettings.defaults();

    private Duration leaseDuration = DEFAULTS.leaseDuration();
    private Duration renewInterval = DEFAULTS.renewInterval();
    private Duration checkInterval = DEFAULTS.checkInterval();

    /**
     * @throws SagaForgeConfigurationException if the three durations do not fit together, naming
     *     the properties
     */
    LeadershipSettings toSettings() {
      try {
        return new LeadershipSettings(leaseDuration, renewInterval, checkInterval);
      } catch (IllegalArgumentException invalid) {
        throw new SagaForgeConfigurationException(
            "saga-forge.transport.leadership.* is invalid: " + invalid.getMessage() + ".",
            "Make saga-forge.transport.leadership.renew-interval and check-interval positive and"
                + " shorter than saga-forge.transport.leadership.lease-duration.");
      }
    }

    public Duration getLeaseDuration() {
      return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
      this.leaseDuration = leaseDuration;
    }

    public Duration getRenewInterval() {
      return renewInterval;
    }

    public void setRenewInterval(Duration renewInterval) {
      this.renewInterval = renewInterval;
    }

    public Duration getCheckInterval() {
      return checkInterval;
    }

    public void setCheckInterval(Duration checkInterval) {
      this.checkInterval = checkInterval;
    }
  }
}
