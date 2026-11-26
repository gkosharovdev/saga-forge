package io.github.gkosharovdev.sagaforge.example.spring;

import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCreatedEvent;
import java.math.BigDecimal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application code that places an order: saves it and publishes {@link OrderCreatedEvent} through
 * Spring's {@link ApplicationEventPublisher} in one transaction. Saga Forge stores the event in the
 * message store together with the order, so the saga starts once both committed, and Spring
 * listeners receive the same publication ({@code specs/014-publish-once}).
 */
@Service
public class OrderPlacement {

  private final OrderRepository orders;
  private final ApplicationEventPublisher events;

  public OrderPlacement(OrderRepository orders, ApplicationEventPublisher events) {
    this.orders = orders;
    this.events = events;
  }

  @Transactional
  public void place(String orderId, BigDecimal amount, boolean inStock) {
    orders.save(Order.create(orderId, inStock));
    events.publishEvent(new OrderCreatedEvent(orderId, amount));
  }
}
