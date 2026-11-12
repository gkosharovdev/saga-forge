package io.github.gkosharovdev.sagaforge.spring.telemetry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Hands the application's {@link OpenTelemetry} bean to the runtime and the transport ({@code specs/010-otel-observability}
 * FR-014). Spring Boot's OpenTelemetry auto-configuration, or Micrometer Tracing's OpenTelemetry bridge, provides the
 * bean; without one, the runtime and transport get {@link OpenTelemetry#noop()} and behave as before. The
 * {@code saga-forge.telemetry.*} properties become the {@link SagaTelemetrySettings}. Backs off for an application
 * {@link SagaForgeTelemetry} bean.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
        "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration" })
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SagaForgeProperties.class)
public class SagaForgeTelemetryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SagaForgeTelemetry sagaForgeTelemetry(SagaForgeProperties properties,
            ObjectProvider<OpenTelemetry> openTelemetry, ObjectProvider<SagaTelemetrySettings> settings) {
        SagaForgeProperties.Telemetry telemetry = properties.getTelemetry();
        if (!telemetry.isEnabled()) {
            return SagaForgeTelemetry.none();
        }
        SagaTelemetrySettings resolved = settings.getIfAvailable(() -> SagaTelemetrySettings.defaults()
                .withTracing(telemetry.getTracing().isEnabled())
                .withMetrics(telemetry.getMetrics().isEnabled())
                .withRecordAssociationValues(telemetry.isRecordAssociationValues())
                .withRecordContents(telemetry.isRecordContents())
                .withRecordFailureMessages(telemetry.isRecordFailureMessages()));
        return new SagaForgeTelemetry(openTelemetry.getIfUnique(OpenTelemetry::noop), resolved);
    }
}
