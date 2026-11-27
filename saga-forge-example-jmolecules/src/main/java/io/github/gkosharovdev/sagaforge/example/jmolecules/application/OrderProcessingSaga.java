package io.github.gkosharovdev.sagaforge.example.jmolecules.application;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderCompleted;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderCreated;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.PaymentProcessed;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.ProcessPayment;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.RefundPayment;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.ReserveStock;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.StockUnavailable;
import java.math.BigDecimal;

/**
 * {@code saga-forge-example}'s canonical saga, unchanged in shape, over the jMolecules domain: an
 * order is paid for, its stock reserved, and the saga ends; a stock shortage after payment refunds
 * the payment. Each step associates its event through {@code orderId}, an {@code OrderId} whose
 * text form is its value.
 */
@Saga
public class OrderProcessingSaga {

  @SagaId private String sagaId;

  private SagaContext context;

  private CommandGateway commandGateway;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void handleOrderCreated(OrderCreated event) {
    context.putState("orderAmount", event.amount().toPlainString());
    commandGateway.send(new ProcessPayment(event.orderId(), event.amount()));
  }

  @SagaStep(associationProperty = "orderId")
  public void processPayment(PaymentProcessed event) {
    context.putState("paymentId", event.paymentId());
    commandGateway.send(new ReserveStock(event.orderId()));
  }

  @EndSaga
  @SagaStep(associationProperty = "orderId")
  public void completeOrder(OrderCompleted event) {
    // Terminal step: no further command to dispatch.
  }

  @StartCompensation
  @SagaStep(associationProperty = "orderId")
  public void rejectOrder(StockUnavailable event) {
    // The trigger records why; the compensation reads what it needs from the SagaContext.
  }

  /** Undoes {@code processPayment}; the saga's id is the refund's stable key. */
  @SagaCompensation(forStep = "processPayment")
  public void compensatePayment(CompensationTrigger trigger) {
    String paymentId = context.getState("paymentId", String.class).orElseThrow();
    BigDecimal amount = new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
    commandGateway.send(new RefundPayment(sagaId, paymentId, amount));
  }
}
