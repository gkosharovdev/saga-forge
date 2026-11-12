package io.github.gkosharovdev.sagaforge.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** specs/010-otel-observability tasks.md T002. */
class SagaTelemetrySettingsTest {

    @Test
    @DisplayName("defaults record spans and metrics but no detail")
    void defaults() {
        SagaTelemetrySettings settings = SagaTelemetrySettings.defaults();

        assertThat(settings.tracing()).isTrue();
        assertThat(settings.metrics()).isTrue();
        assertThat(settings.recordAssociationValues()).isFalse();
        assertThat(settings.recordContents()).isFalse();
        assertThat(settings.recordFailureMessages()).isFalse();
    }

    @Test
    @DisplayName("each with-method changes one switch and leaves the original unchanged")
    void withMethods() {
        SagaTelemetrySettings defaults = SagaTelemetrySettings.defaults();

        assertThat(defaults.withTracing(false)).isEqualTo(new SagaTelemetrySettings(false, true, false, false, false));
        assertThat(defaults.withMetrics(false)).isEqualTo(new SagaTelemetrySettings(true, false, false, false, false));
        assertThat(defaults.withRecordAssociationValues(true))
                .isEqualTo(new SagaTelemetrySettings(true, true, true, false, false));
        assertThat(defaults.withRecordContents(true)).isEqualTo(new SagaTelemetrySettings(true, true, false, true, false));
        assertThat(defaults.withRecordFailureMessages(true))
                .isEqualTo(new SagaTelemetrySettings(true, true, false, false, true));
        assertThat(defaults).isEqualTo(SagaTelemetrySettings.defaults());
    }
}
