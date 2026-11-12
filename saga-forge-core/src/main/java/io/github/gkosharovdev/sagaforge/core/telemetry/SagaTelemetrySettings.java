package io.github.gkosharovdev.sagaforge.core.telemetry;

/**
 * What the runtime and the in-memory transport record through OpenTelemetry ({@code specs/010-otel-observability}
 * FR-013, FR-014). Spans and metrics are on by default; the three details that may carry business or personal data are
 * off by default and switched on one by one.
 *
 * <p>Immutable and thread-safe.</p>
 *
 * @param tracing                 whether spans are recorded
 * @param metrics                 whether counters, gauges and duration histograms are recorded
 * @param recordAssociationValues whether spans carry association values (an order id, say)
 * @param recordContents          whether spans carry event and command contents ({@code toString}, at most 1,000
 *                                characters)
 * @param recordFailureMessages   whether spans carry failure messages and exception events; the failure's type is
 *                                always recorded
 */
public record SagaTelemetrySettings(boolean tracing, boolean metrics, boolean recordAssociationValues,
        boolean recordContents, boolean recordFailureMessages) {

    private static final SagaTelemetrySettings DEFAULTS = new SagaTelemetrySettings(true, true, false, false, false);

    /** Tracing and metrics on, every detail off. */
    public static SagaTelemetrySettings defaults() {
        return DEFAULTS;
    }

    public SagaTelemetrySettings withTracing(boolean on) {
        return new SagaTelemetrySettings(on, metrics, recordAssociationValues, recordContents, recordFailureMessages);
    }

    public SagaTelemetrySettings withMetrics(boolean on) {
        return new SagaTelemetrySettings(tracing, on, recordAssociationValues, recordContents, recordFailureMessages);
    }

    public SagaTelemetrySettings withRecordAssociationValues(boolean on) {
        return new SagaTelemetrySettings(tracing, metrics, on, recordContents, recordFailureMessages);
    }

    public SagaTelemetrySettings withRecordContents(boolean on) {
        return new SagaTelemetrySettings(tracing, metrics, recordAssociationValues, on, recordFailureMessages);
    }

    public SagaTelemetrySettings withRecordFailureMessages(boolean on) {
        return new SagaTelemetrySettings(tracing, metrics, recordAssociationValues, recordContents, on);
    }
}
