package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

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
import java.math.BigDecimal;

/**
 * {@code saga-forge-example}'s canonical saga over a domain marked only with jMolecules: the saga
 * side keeps Saga Forge's annotations; events, commands, the aggregate and the handlers do not.
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

  @SagaCompensation(forStep = "processPayment")
  public void compensatePayment(CompensationTrigger trigger) {
    String paymentId = context.getState("paymentId", String.class).orElseThrow();
    BigDecimal amount = new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
    commandGateway.send(new RefundPayment(sagaId, paymentId, amount));
  }
}
