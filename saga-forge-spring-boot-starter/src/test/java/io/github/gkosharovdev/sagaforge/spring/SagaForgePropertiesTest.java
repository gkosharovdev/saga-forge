package io.github.gkosharovdev.sagaforge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.StorageType;
import io.github.gkosharovdev.sagaforge.spring.events.ApplicationEventsMode;

/** Binding and validation of {@code saga-forge.*} ({@code specs/008-spring-boot-starter} data-model.md, FR-015). */
class SagaForgePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("defaults")
    void defaults() {
        runner.run(context -> {
            SagaForgeProperties properties = context.getBean(SagaForgeProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getScanPackages()).isEmpty();
            assertThat(properties.getStorage().getType()).isNull();
            assertThat(properties.getStorage().getPostgres().getInitializeSchema()).isEqualTo(SchemaInitialization.NEVER);
            assertThat(properties.getRuntime().getMaxTotalRetryBackoff()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getApplicationEvents().getMode()).isEqualTo(ApplicationEventsMode.IN_TRANSACTION);
        });
    }

    @Test
    @DisplayName("every property binds, in relaxed form")
    void binding() {
        runner.withPropertyValues("saga-forge.enabled=false", "saga-forge.scan-packages=com.example.a,com.example.b",
                "saga-forge.storage.type=postgres", "saga-forge.storage.postgres.initialize-schema=always",
                "saga-forge.runtime.max-total-retry-backoff=2m", "saga-forge.application-events.mode=after-delivery")
                .run(context -> {
                    SagaForgeProperties properties = context.getBean(SagaForgeProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getScanPackages()).containsExactly("com.example.a", "com.example.b");
                    assertThat(properties.getStorage().getType()).isEqualTo(StorageType.POSTGRES);
                    assertThat(properties.getStorage().getPostgres().getInitializeSchema())
                            .isEqualTo(SchemaInitialization.ALWAYS);
                    assertThat(properties.getRuntime().getMaxTotalRetryBackoff()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getApplicationEvents().getMode())
                            .isEqualTo(ApplicationEventsMode.AFTER_DELIVERY);
                });
    }

    @Test
    @DisplayName("an unknown enum value fails binding, naming the property")
    void unknownValue() {
        runner.withPropertyValues("saga-forge.storage.postgres.initialize-schema=sometimes").run(context ->
                assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("saga-forge.storage.postgres.initialize-schema"));
    }

    @Test
    @DisplayName("a non-positive retry backoff fails validation, naming the property")
    void invalidBackoff() {
        runner.withPropertyValues("saga-forge.runtime.max-total-retry-backoff=0s").run(context -> {
            SagaForgeProperties properties = context.getBean(SagaForgeProperties.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(properties::validate)
                    .isInstanceOf(SagaForgeConfigurationException.class)
                    .hasMessageContaining("saga-forge.runtime.max-total-retry-backoff");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SagaForgeProperties.class)
    static class Config {
    }
}
