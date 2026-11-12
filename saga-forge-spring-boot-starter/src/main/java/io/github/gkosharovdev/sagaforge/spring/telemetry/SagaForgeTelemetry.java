package io.github.gkosharovdev.sagaforge.spring.telemetry;

import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.opentelemetry.api.OpenTelemetry;

/**
 * What the starter hands to the runtime and the transport ({@code specs/010-otel-observability} FR-014): the
 * application's OpenTelemetry instance and the settings built from {@code saga-forge.telemetry.*}. Define a bean of this
 * type to replace both.
 *
 * @param openTelemetry where spans and metrics go; {@link OpenTelemetry#noop()} when the application has none
 * @param settings      what is recorded
 */
public record SagaForgeTelemetry(OpenTelemetry openTelemetry, SagaTelemetrySettings settings) {

    /** No telemetry. */
    public static SagaForgeTelemetry none() {
        return new SagaForgeTelemetry(OpenTelemetry.noop(), SagaTelemetrySettings.defaults());
    }
}
