package io.github.gkosharovdev.sagaforge.spring;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * The application's transaction manager as Saga Forge's {@link TransactionBoundary}, shared by storage, message store,
 * transport and runtime, so saga writes, outbox writes and the application's own writes share one transaction
 * ({@code specs/008-spring-boot-starter} FR-009).
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration" })
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
public class SpringTransactionConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionBoundary.class)
    public SpringTransactionBoundary sagaForgeTransactionBoundary(PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new SpringTransactionBoundary(transactionManager, dataSource);
    }
}
