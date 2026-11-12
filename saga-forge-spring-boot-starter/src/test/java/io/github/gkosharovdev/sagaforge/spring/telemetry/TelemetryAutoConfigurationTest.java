package io.github.gkosharovdev.sagaforge.spring.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/** User Story 4: the starter hands the application's OpenTelemetry to Saga Forge (specs/010-otel-observability T033). */
class TelemetryAutoConfigurationTest {

    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().build();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SagaForgeTelemetryConfiguration.class));

    @Test
    @DisplayName("the application's OpenTelemetry bean is used, with default settings")
    void applicationOpenTelemetry() {
        runner.withBean(OpenTelemetry.class, () -> sdk).run(context -> {
            SagaForgeTelemetry telemetry = context.getBean(SagaForgeTelemetry.class);
            assertThat(telemetry.openTelemetry()).isSameAs(sdk);
            assertThat(telemetry.settings()).isEqualTo(SagaTelemetrySettings.defaults());
        });
    }

    @Test
    @DisplayName("without an OpenTelemetry bean, no-op")
    void noOpenTelemetry() {
        runner.run(context -> assertThat(context.getBean(SagaForgeTelemetry.class).openTelemetry())
                .isSameAs(OpenTelemetry.noop()));
    }

    @Test
    @DisplayName("saga-forge.telemetry.enabled=false gives no-op even with an OpenTelemetry bean")
    void disabled() {
        runner.withBean(OpenTelemetry.class, () -> sdk).withPropertyValues("saga-forge.telemetry.enabled=false")
                .run(context -> assertThat(context.getBean(SagaForgeTelemetry.class).openTelemetry())
                        .isSameAs(OpenTelemetry.noop()));
    }

    @Test
    @DisplayName("the properties become the settings")
    void properties() {
        runner.withBean(OpenTelemetry.class, () -> sdk).withPropertyValues(
                "saga-forge.telemetry.tracing.enabled=false", "saga-forge.telemetry.metrics.enabled=false",
                "saga-forge.telemetry.record-association-values=true", "saga-forge.telemetry.record-contents=true",
                "saga-forge.telemetry.record-failure-messages=true")
                .run(context -> assertThat(context.getBean(SagaForgeTelemetry.class).settings())
                        .isEqualTo(new SagaTelemetrySettings(false, false, true, true, true)));
    }

    @Test
    @DisplayName("an application SagaTelemetrySettings or SagaForgeTelemetry bean wins")
    void overrides() {
        SagaTelemetrySettings own = SagaTelemetrySettings.defaults().withRecordContents(true);
        runner.withBean(OpenTelemetry.class, () -> sdk).withBean(SagaTelemetrySettings.class, () -> own)
                .run(context -> assertThat(context.getBean(SagaForgeTelemetry.class).settings()).isSameAs(own));
        SagaForgeTelemetry replaced = SagaForgeTelemetry.none();
        runner.withBean(SagaForgeTelemetry.class, () -> replaced)
                .run(context -> assertThat(context.getBean(SagaForgeTelemetry.class)).isSameAs(replaced));
    }
}
