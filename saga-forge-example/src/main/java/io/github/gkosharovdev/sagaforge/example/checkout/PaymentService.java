package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.AuthorizePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CapturePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleasePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentAuthorizedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentDeclinedEvent;

/**
 * The Payments context's command handlers, in front of the {@link PaymentProvider}. Each handler
 * passes the command's {@link CommandId} on as the provider's idempotency key, so a retried step or
 * a redelivered outbox command never charges, captures or refunds twice.
 */
public class PaymentService {

  private final PaymentProvider provider;

  public PaymentService(PaymentProvider provider) {
    this.provider = provider;
  }

  public PaymentService() {
    this(new PaymentProvider());
  }

  /**
   * Authorises the amount; a decline is a business answer, recorded as {@link
   * PaymentDeclinedEvent}.
   */
  @CommandHandler
  public void handle(AuthorizePaymentCommand command, CommandId commandId) {
    provider
        .authorize(commandId.value(), command.amount())
        .ifPresentOrElse(
            paymentId ->
                DomainEvents.record(
                    new PaymentAuthorizedEvent(paymentId, command.orderId(), command.amount())),
            () ->
                DomainEvents.record(
                    new PaymentDeclinedEvent(
                        "declined-" + commandId.value(),
                        command.orderId(),
                        provider.declineReason())));
  }

  @CommandHandler
  public void handle(CapturePaymentCommand command) {
    provider.capture(command.paymentId());
  }

  /**
   * Gives the money back the way the payment's state requires: a refund once it was captured, a
   * void while it is only authorised. That rule belongs here, in Payments, not in the saga.
   */
  @CommandHandler
  public void handle(ReleasePaymentCommand command) {
    if (provider.state(command.paymentId()) == PaymentProvider.State.CAPTURED) {
      provider.refund(command.paymentId());
    } else {
      provider.voidAuthorization(command.paymentId());
    }
  }
}
