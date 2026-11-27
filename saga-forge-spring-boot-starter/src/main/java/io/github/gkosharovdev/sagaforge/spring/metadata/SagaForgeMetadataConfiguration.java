package io.github.gkosharovdev.sagaforge.spring.metadata;

import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Process metadata over SLF4J's MDC ({@code specs/019-process-metadata}): with {@code
 * saga-forge.metadata.mdc-keys} set and SLF4J on the class path, a {@link MetadataAccessor} bean
 * for those keys, which the runtime and the transport receive. Backs off for an application {@code
 * MetadataAccessor} bean. Without SLF4J the property is ignored with a startup warning.
 */
@AutoConfiguration(
    beforeName = {
      "io.github.gkosharovdev.sagaforge.spring.runtime.SagaRuntimeConfiguration",
      "io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration"
    })
@ConditionalOnProperty(
    prefix = "saga-forge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(SagaForgeProperties.class)
public class SagaForgeMetadataConfiguration {

  static final String MDC_KEYS = "saga-forge.metadata.mdc-keys";

  /**
   * The {@code metadata} object of the {@code sagaforge} endpoint: {@code accessor} is {@code
   * "none"}, {@code "mdc"} (with {@code mdcKeys}) or the class name of an application accessor.
   *
   * @param accessor the accessor the runtime uses, or {@code null} for none
   * @return the view; never {@code null}
   */
  public static Map<String, Object> describe(MetadataAccessor accessor) {
    if (accessor == null || accessor == MetadataAccessor.NONE) {
      return Map.of("accessor", "none");
    }
    if (accessor instanceof MdcMetadataAccessor mdc) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("accessor", "mdc");
      view.put("mdcKeys", mdc.keys());
      return view;
    }
    return Map.of("accessor", accessor.getClass().getName());
  }

  /** The MDC accessor, when keys are configured and SLF4J is present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.slf4j.MDC")
  @Conditional(MdcKeysConfigured.class)
  static class Mdc {

    @Bean
    @ConditionalOnMissingBean(MetadataAccessor.class)
    MetadataAccessor sagaForgeMdcMetadataAccessor(SagaForgeProperties properties) {
      return new MdcMetadataAccessor(properties.getMetadata().getMdcKeys());
    }
  }

  /** A startup warning when keys are configured but SLF4J is absent. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnMissingClass("org.slf4j.MDC")
  @Conditional(MdcKeysConfigured.class)
  static class NoMdc {

    @Bean
    SmartInitializingSingleton sagaForgeMdcKeysIgnored() {
      return () ->
          System.getLogger("io.github.gkosharovdev.sagaforge.spring")
              .log(
                  Level.WARNING,
                  "saga.metadata.mdcUnavailable: "
                      + MDC_KEYS
                      + " is set, but org.slf4j.MDC is not on the class path; no metadata is"
                      + " captured");
    }
  }

  /** Matches when {@code saga-forge.metadata.mdc-keys} holds at least one key. */
  static final class MdcKeysConfigured extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      List<String> keys =
          Binder.get(context.getEnvironment())
              .bind(MDC_KEYS, Bindable.listOf(String.class))
              .orElse(List.of());
      return keys.isEmpty()
          ? ConditionOutcome.noMatch(MDC_KEYS + " is empty")
          : ConditionOutcome.match(MDC_KEYS + " is " + keys);
    }
  }
}
