package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaRuntimeConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The {@code sagas} Actuator endpoint over the runtime's operations ({@code
 * specs/015-saga-operations} contracts/starter-operations.md). Registered when Actuator is on the
 * class path and a {@link SagaRuntime} bean exists, with or without a transport; switched by Spring
 * Boot's {@code management.endpoint.sagas.*} and {@code management.endpoints.web.exposure.*}.
 */
@AutoConfiguration(after = SagaRuntimeConfiguration.class)
@ConditionalOnProperty(
    prefix = "saga-forge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnClass(
    name = {
      "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
      "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint"
    })
@ConditionalOnBean(SagaRuntime.class)
public class SagasEndpointConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnAvailableEndpoint(endpoint = SagasEndpoint.class)
  public SagasEndpoint sagasEndpoint(SagaRuntime runtime, SagaForgeProperties properties) {
    return new SagasEndpoint(
        runtime::operations, properties.getOperations().isExposeContextValues());
  }
}
