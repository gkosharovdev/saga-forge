package io.github.gkosharovdev.sagaforge.spring;

import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.AbandonedStartPolicy;
import io.github.gkosharovdev.sagaforge.runtime.RecoverySettings;
import io.github.gkosharovdev.sagaforge.spring.events.ApplicationEventsMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Saga Forge's Spring Boot properties, {@code saga-forge.*} ({@code specs/008-spring-boot-starter}
 * data-model.md). The in-memory transport's tuning lives under {@code saga-forge.transport.*}
 * (007's {@code TransportProperties}).
 */
@ConfigurationProperties("saga-forge")
public class SagaForgeProperties {

  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

  /** Whether Saga Forge's auto-configuration is active. */
  private boolean enabled = true;

  /**
   * Packages scanned for saga model classes in addition to the application's auto-configuration
   * packages.
   */
  private List<String> scanPackages = new ArrayList<>();

  private final Storage storage = new Storage();

  private final Runtime runtime = new Runtime();

  private final ApplicationEvents applicationEvents = new ApplicationEvents();

  private final Telemetry telemetry = new Telemetry();

  private final OutcomeEvents outcomeEvents = new OutcomeEvents();

  private final Recovery recovery = new Recovery();

  private final Operations operations = new Operations();

  /**
   * Checks values the binder cannot check itself.
   *
   * @throws SagaForgeConfigurationException naming the property
   */
  public void validate() {
    for (String name : scanPackages) {
      if (name == null || !PACKAGE.matcher(name).matches()) {
        throw new SagaForgeConfigurationException(
            "saga-forge.scan-packages contains '" + name + "', which is not a Java package name.",
            "List package names such as com.example.orders.");
      }
    }
    Duration backoff = runtime.getMaxTotalRetryBackoff();
    if (backoff == null || backoff.isNegative() || backoff.isZero()) {
      throw new SagaForgeConfigurationException(
          "saga-forge.runtime.max-total-retry-backoff must be a positive duration, but is "
              + backoff
              + ".",
          "Set it to a positive duration such as 30s, or remove it to use the default.");
    }
    requirePositive("saga-forge.recovery.interval", recovery.getInterval());
    requirePositive("saga-forge.recovery.stall-age", recovery.getStallAge());
    requirePositive("saga-forge.recovery.abandonment-age", recovery.getAbandonmentAge());
    requirePositive(
        "saga-forge.telemetry.instance-count-interval", telemetry.getInstanceCountInterval());
    int batchSize = recovery.getBatchSize();
    if (batchSize < 1 || batchSize > RecoverySettings.MAX_BATCH_SIZE) {
      throw new SagaForgeConfigurationException(
          "saga-forge.recovery.batch-size must be between 1 and "
              + RecoverySettings.MAX_BATCH_SIZE
              + ", but is "
              + batchSize
              + ".",
          "Set it to a number such as 100, or remove it to use the default.");
    }
    if (recovery.getAbandonedStartPolicy() == null) {
      throw new SagaForgeConfigurationException(
          "saga-forge.recovery.abandoned-start-policy must be report or fail.",
          "Set it to report or fail, or remove it to use the default (report).");
    }
  }

  private static void requirePositive(String property, Duration value) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new SagaForgeConfigurationException(
          property + " must be a positive duration, but is " + value + ".",
          "Set it to a positive duration such as 1m, or remove it to use the default.");
    }
  }

  /**
   * The runtime's recovery settings from {@code saga-forge.recovery.*}; call {@link #validate()}
   * first.
   */
  public RecoverySettings recoverySettings() {
    return new RecoverySettings(
        recovery.isEnabled(),
        recovery.getInterval(),
        recovery.getStallAge(),
        recovery.getBatchSize(),
        recovery.getAbandonmentAge(),
        recovery.getAbandonedStartPolicy());
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getScanPackages() {
    return scanPackages;
  }

  public void setScanPackages(List<String> scanPackages) {
    this.scanPackages = scanPackages;
  }

  public Storage getStorage() {
    return storage;
  }

  public Runtime getRuntime() {
    return runtime;
  }

  public ApplicationEvents getApplicationEvents() {
    return applicationEvents;
  }

  public Telemetry getTelemetry() {
    return telemetry;
  }

  public OutcomeEvents getOutcomeEvents() {
    return outcomeEvents;
  }

  public Recovery getRecovery() {
    return recovery;
  }

  public Operations getOperations() {
    return operations;
  }

  /**
   * {@code saga-forge.recovery.*}: sweeps that resume stalled compensations and handle abandoned
   * starts (specs/015-saga-operations).
   */
  public static class Recovery {

    /** Whether the runtime runs recovery sweeps on its own. */
    private boolean enabled = true;

    /** The time between two sweeps. */
    private Duration interval = Duration.ofMinutes(1);

    /** How long a compensating saga stays unmodified before a sweep resumes it. */
    private Duration stallAge = Duration.ofMinutes(5);

    /** How many sagas of each kind one sweep handles at most. */
    private int batchSize = 100;

    /** How long a created saga with no completed step waits before a sweep handles it. */
    private Duration abandonmentAge = Duration.ofMinutes(15);

    /**
     * What a sweep does with an abandoned start: report logs and counts it, fail ends it FAILED.
     */
    private AbandonedStartPolicy abandonedStartPolicy = AbandonedStartPolicy.REPORT;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getInterval() {
      return interval;
    }

    public void setInterval(Duration interval) {
      this.interval = interval;
    }

    public Duration getStallAge() {
      return stallAge;
    }

    public void setStallAge(Duration stallAge) {
      this.stallAge = stallAge;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public Duration getAbandonmentAge() {
      return abandonmentAge;
    }

    public void setAbandonmentAge(Duration abandonmentAge) {
      this.abandonmentAge = abandonmentAge;
    }

    public AbandonedStartPolicy getAbandonedStartPolicy() {
      return abandonedStartPolicy;
    }

    public void setAbandonedStartPolicy(AbandonedStartPolicy abandonedStartPolicy) {
      this.abandonedStartPolicy = abandonedStartPolicy;
    }
  }

  /** {@code saga-forge.operations.*}: the sagas Actuator endpoint (specs/015-saga-operations). */
  public static class Operations {

    /** Whether the sagas endpoint shows a saga's context values; they may hold personal data. */
    private boolean exposeContextValues;

    public boolean isExposeContextValues() {
      return exposeContextValues;
    }

    public void setExposeContextValues(boolean exposeContextValues) {
      this.exposeContextValues = exposeContextValues;
    }
  }

  /**
   * {@code saga-forge.outcome-events.*}: the events announcing how a saga ended
   * (specs/011-saga-outcome-events).
   */
  public static class OutcomeEvents {

    /**
     * Whether a saga reaching a terminal status records an outcome event (SagaCompleted,
     * SagaFailed, SagaCompensated).
     */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** {@code saga-forge.storage.*}. */
  public static class Storage {

    /**
     * Which storage module to use when several are on the class path; detected when only one is.
     */
    private StorageType type;

    private final Postgres postgres = new Postgres();

    public StorageType getType() {
      return type;
    }

    public void setType(StorageType type) {
      this.type = type;
    }

    public Postgres getPostgres() {
      return postgres;
    }
  }

  /** {@code saga-forge.storage.postgres.*}. */
  public static class Postgres {

    /**
     * Whether the starter creates Saga Forge's tables from schema.sql when they do not exist yet.
     */
    private SchemaInitialization initializeSchema = SchemaInitialization.NEVER;

    public SchemaInitialization getInitializeSchema() {
      return initializeSchema;
    }

    public void setInitializeSchema(SchemaInitialization initializeSchema) {
      this.initializeSchema = initializeSchema;
    }
  }

  /** {@code saga-forge.runtime.*}. */
  public static class Runtime {

    /** Longest total wait a step's retry policy may add up to; longer policies fail startup. */
    private Duration maxTotalRetryBackoff = Duration.ofSeconds(30);

    public Duration getMaxTotalRetryBackoff() {
      return maxTotalRetryBackoff;
    }

    public void setMaxTotalRetryBackoff(Duration maxTotalRetryBackoff) {
      this.maxTotalRetryBackoff = maxTotalRetryBackoff;
    }
  }

  /** {@code saga-forge.application-events.*}. */
  public static class ApplicationEvents {

    /**
     * How domain events recorded in saga units of work reach Spring application-event listeners.
     */
    private ApplicationEventsMode mode = ApplicationEventsMode.IN_TRANSACTION;

    public ApplicationEventsMode getMode() {
      return mode;
    }

    public void setMode(ApplicationEventsMode mode) {
      this.mode = mode;
    }
  }

  /** Storage modules the starter can select. */
  public enum StorageType {
    /** {@code saga-forge-storage-postgres}. */
    POSTGRES
  }

  /** Schema initialization policies. */
  public enum SchemaInitialization {
    /**
     * Never create tables; missing tables fail startup. The default, for Flyway or Liquibase users.
     */
    NEVER,
    /**
     * Create Saga Forge's tables from schema.sql when they do not exist yet; never alters existing
     * tables.
     */
    ALWAYS
  }

  /**
   * OpenTelemetry instrumentation of the runtime and the transport ({@code
   * specs/010-otel-observability}).
   */
  public static class Telemetry {

    /**
     * Whether the application's OpenTelemetry instance is handed to the runtime and the transport.
     */
    private boolean enabled = true;

    /**
     * Record saga event and command contents ({@code toString}, at most 1,000 characters) on spans.
     */
    private boolean recordContents;

    /** Record association values (an order id, say) on spans. */
    private boolean recordAssociationValues;

    /**
     * Record failure messages and exception events on spans; the failure's type is always recorded.
     */
    private boolean recordFailureMessages;

    /** How often the saga_forge.instances gauge recounts the saga instances by status. */
    private Duration instanceCountInterval = SagaTelemetrySettings.DEFAULT_INSTANCE_COUNT_INTERVAL;

    private final Signal tracing = new Signal();

    private final Signal metrics = new Signal();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isRecordContents() {
      return recordContents;
    }

    public void setRecordContents(boolean recordContents) {
      this.recordContents = recordContents;
    }

    public boolean isRecordAssociationValues() {
      return recordAssociationValues;
    }

    public void setRecordAssociationValues(boolean recordAssociationValues) {
      this.recordAssociationValues = recordAssociationValues;
    }

    public boolean isRecordFailureMessages() {
      return recordFailureMessages;
    }

    public void setRecordFailureMessages(boolean recordFailureMessages) {
      this.recordFailureMessages = recordFailureMessages;
    }

    public Duration getInstanceCountInterval() {
      return instanceCountInterval;
    }

    public void setInstanceCountInterval(Duration instanceCountInterval) {
      this.instanceCountInterval = instanceCountInterval;
    }

    public Signal getTracing() {
      return tracing;
    }

    public Signal getMetrics() {
      return metrics;
    }
  }

  /** One telemetry signal that can be switched off. */
  public static class Signal {

    /** Whether this signal is recorded. */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
