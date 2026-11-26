package io.github.gkosharovdev.sagaforge.core.telemetry;

import java.time.Duration;

/**
 * What the runtime and the in-memory transport record through OpenTelemetry ({@code
 * specs/010-otel-observability} FR-013, FR-014). Spans and metrics are on by default; the three
 * details that may carry business or personal data are off by default and switched on one by one.
 *
 * <p>Immutable and thread-safe.
 *
 * @param tracing whether spans are recorded
 * @param metrics whether counters, gauges and duration histograms are recorded
 * @param recordAssociationValues whether spans carry association values (an order id, say)
 * @param recordContents whether spans carry event and command contents ({@code toString}, at most
 *     1,000 characters)
 * @param recordFailureMessages whether spans carry failure messages and exception events; the
 *     failure's type is always recorded
 */
public record SagaTelemetrySettings(
    boolean tracing,
    boolean metrics,
    boolean recordAssociationValues,
    boolean recordContents,
    boolean recordFailureMessages,
    Duration instanceCountInterval) {

  /** The default {@link #instanceCountInterval()}. */
  public static final Duration DEFAULT_INSTANCE_COUNT_INTERVAL = Duration.ofSeconds(30);

  private static final SagaTelemetrySettings DEFAULTS =
      new SagaTelemetrySettings(true, true, false, false, false, DEFAULT_INSTANCE_COUNT_INTERVAL);

  /**
   * Throws {@link IllegalArgumentException} if {@code instanceCountInterval} is {@code null}, zero
   * or negative.
   */
  public SagaTelemetrySettings {
    if (instanceCountInterval == null
        || instanceCountInterval.isZero()
        || instanceCountInterval.isNegative()) {
      throw new IllegalArgumentException(
          "instanceCountInterval must be positive, but is " + instanceCountInterval);
    }
  }

  /** Settings with the default {@link #instanceCountInterval()}, as before 015. */
  public SagaTelemetrySettings(
      boolean tracing,
      boolean metrics,
      boolean recordAssociationValues,
      boolean recordContents,
      boolean recordFailureMessages) {
    this(
        tracing,
        metrics,
        recordAssociationValues,
        recordContents,
        recordFailureMessages,
        DEFAULT_INSTANCE_COUNT_INTERVAL);
  }

  /** Tracing and metrics on, every detail off. */
  public static SagaTelemetrySettings defaults() {
    return DEFAULTS;
  }

  public SagaTelemetrySettings withTracing(boolean on) {
    return new SagaTelemetrySettings(
        on,
        metrics,
        recordAssociationValues,
        recordContents,
        recordFailureMessages,
        instanceCountInterval);
  }

  public SagaTelemetrySettings withMetrics(boolean on) {
    return new SagaTelemetrySettings(
        tracing,
        on,
        recordAssociationValues,
        recordContents,
        recordFailureMessages,
        instanceCountInterval);
  }

  public SagaTelemetrySettings withRecordAssociationValues(boolean on) {
    return new SagaTelemetrySettings(
        tracing, metrics, on, recordContents, recordFailureMessages, instanceCountInterval);
  }

  public SagaTelemetrySettings withRecordContents(boolean on) {
    return new SagaTelemetrySettings(
        tracing,
        metrics,
        recordAssociationValues,
        on,
        recordFailureMessages,
        instanceCountInterval);
  }

  public SagaTelemetrySettings withRecordFailureMessages(boolean on) {
    return new SagaTelemetrySettings(
        tracing, metrics, recordAssociationValues, recordContents, on, instanceCountInterval);
  }

  /**
   * @param interval how long the {@code saga_forge.instances} gauge serves cached counts before it
   *     counts again ({@code specs/015-saga-operations} FR-031); positive
   */
  public SagaTelemetrySettings withInstanceCountInterval(Duration interval) {
    return new SagaTelemetrySettings(
        tracing, metrics, recordAssociationValues, recordContents, recordFailureMessages, interval);
  }
}
