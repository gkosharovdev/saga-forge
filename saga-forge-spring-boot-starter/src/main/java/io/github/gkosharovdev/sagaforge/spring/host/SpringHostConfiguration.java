package io.github.gkosharovdev.sagaforge.spring.host;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.repository.support.Repositories;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;

/**
 * Spring implementations of the runtime's host SPIs ({@code specs/008-spring-boot-starter} FR-010 to FR-013,
 * contracts/spring-host-spis.md); each gives way to an application bean of its SPI type.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpringHostConfiguration {

    @Bean
    @ConditionalOnMissingBean(SagaObjectProvider.class)
    public SpringSagaObjectProvider sagaForgeSagaObjects(ConfigurableListableBeanFactory beanFactory) {
        return new SpringSagaObjectProvider(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean(HandlerInstanceProvider.class)
    public SpringHandlerInstanceProvider sagaForgeHandlerInstances(ConfigurableListableBeanFactory beanFactory) {
        return new SpringHandlerInstanceProvider(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean(HandlerParameterResolver.class)
    public SpringHandlerParameterResolver sagaForgeParameterResolver(ConfigurableListableBeanFactory beanFactory) {
        return new SpringHandlerParameterResolver(beanFactory);
    }

    /** Aggregates through Spring Data repositories, when Spring Data is on the class path. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Repositories.class)
    static class SpringDataAggregateConfiguration {

        @Bean
        @ConditionalOnMissingBean(AggregateResolver.class)
        public SpringDataAggregateResolver sagaForgeAggregateResolver(ConfigurableListableBeanFactory beanFactory) {
            ConversionService conversions = beanFactory.getConversionService() != null
                    ? beanFactory.getConversionService() : DefaultConversionService.getSharedInstance();
            return new SpringDataAggregateResolver(beanFactory, conversions);
        }
    }
}
