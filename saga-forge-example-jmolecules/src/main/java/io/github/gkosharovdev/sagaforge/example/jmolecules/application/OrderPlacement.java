package io.github.gkosharovdev.sagaforge.example.jmolecules.application;

import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Order;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderCreated;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderId;
import java.math.BigDecimal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places an order: saves it and publishes {@link OrderCreated} through Spring in one transaction,
 * so the saga starts once both committed ({@code specs/014-publish-once}).
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
    OrderId id = new OrderId(orderId);
    orders.save(Order.place(id, inStock));
    events.publishEvent(new OrderCreated(id, amount));
  }
}
