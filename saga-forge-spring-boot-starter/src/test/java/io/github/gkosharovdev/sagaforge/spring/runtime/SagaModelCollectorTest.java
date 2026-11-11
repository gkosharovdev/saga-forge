package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.Order;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderApp;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderRepository;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.Payment;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.PaymentService;

/** Model collection ({@code specs/008-spring-boot-starter} FR-002, FR-004, research §1). */
class SagaModelCollectorTest {

    private static final String ORDERS = "io.github.gkosharovdev.sagaforge.spring.fixtures.order";

    private static final List<Class<?>> ORDER_MODEL = List.of(FulfilmentSaga.class, OrderProcessingSaga.class,
            OrderRepository.class, PaymentService.class, Order.class, Payment.class);

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    private List<Class<?>> collect(List<String> scanPackages) {
        return new SagaModelCollector(beanFactory, scanPackages,
                beanFactory.getBeanProvider(SagaModelContributor.class)).collect();
    }

    @Test
    @DisplayName("scans the auto-configuration packages: sagas, then hosts, then aggregates, each by name")
    void scansAutoConfigurationPackages() {
        AutoConfigurationPackages.register(beanFactory, ORDERS);
        assertThat(collect(List.of())).containsExactlyElementsOf(ORDER_MODEL)
                .doesNotContain(OrderApp.class, OrderEvents.class, ProcessPaymentCommand.class);
    }

    @Test
    @DisplayName("saga-forge.scan-packages are scanned as well")
    void scanPackages() {
        assertThat(collect(List.of(ORDERS))).containsExactlyElementsOf(ORDER_MODEL);
    }

    @Test
    @DisplayName("beans with handler methods are collected by their user class, through proxies")
    void beansThroughProxies() {
        ProxyFactory proxy = new ProxyFactory(new OutsideHost());
        proxy.setProxyTargetClass(true);
        OutsideHost host = (OutsideHost) proxy.getProxy();
        RootBeanDefinition definition = new RootBeanDefinition(host.getClass());
        definition.setInstanceSupplier(() -> host);
        beanFactory.registerBeanDefinition("outsideHost", definition);
        beanFactory.registerBeanDefinition("notModel", new RootBeanDefinition(Object.class));
        assertThat(collect(List.of())).containsExactly(OutsideHost.class);
    }

    @Test
    @DisplayName("contributors add their classes; duplicates appear once")
    void contributors() {
        beanFactory.registerSingleton("contributor", (SagaModelContributor) () -> List.of(Order.class, Order.class));
        beanFactory.registerSingleton("other", (SagaModelContributor) () -> List.of(OrderProcessingSaga.class));
        assertThat(collect(List.of())).containsExactly(OrderProcessingSaga.class, Order.class);
    }

    @Test
    @DisplayName("an invalid scan package fails naming the property")
    void invalidScanPackage() {
        SagaForgeProperties properties = new SagaForgeProperties();
        properties.setScanPackages(List.of("com..example"));
        assertThatThrownBy(properties::validate).isInstanceOf(SagaForgeConfigurationException.class)
                .hasMessageContaining("saga-forge.scan-packages");
    }

    /** A handler host outside the scanned packages. */
    public static class OutsideHost {

        @CommandHandler
        public void handle(ProcessPaymentCommand command) {
            // not run
        }
    }
}
