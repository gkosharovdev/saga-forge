package io.github.gkosharovdev.sagaforge.test.fixtures;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * The fixture's own test model, shaped like the canonical order saga but independent of {@code saga-forge-example}:
 * {@code placed} (start) takes the payment, {@code paymentTaken} reserves stock, {@code stockReserved} ends the saga,
 * {@code stockMissing} starts compensation, and {@code refund} undoes {@code paymentTaken}. Amounts are strings, as
 * durable storage needs. {@code placed} retries transient failures: 4 attempts, 10 s apart, 30 s in total.
 */
public final class Shop {

    public static final String PLACED = "placed";
    public static final String PAID = "paymentTaken";
    public static final String RESERVED = "stockReserved";
    public static final String MISSING = "stockMissing";
    public static final String REFUND = "refund";

    private Shop() {
    }

    @Aggregate
    public static final class ShopOrder {

        @AggregateIdentifier
        private String orderId;
        private boolean inStock;
        private String status = "NEW";

        ShopOrder() {
        }

        public static ShopOrder create(String orderId, boolean inStock) {
            ShopOrder order = new ShopOrder();
            order.orderId = orderId;
            order.inStock = inStock;
            return order;
        }

        @CommandHandler
        public void handle(ReserveStock command) {
            if (inStock) {
                status = "RESERVED";
                DomainEvents.record(new StockReserved(orderId));
            } else {
                status = "REJECTED";
                DomainEvents.record(new StockMissing(orderId));
            }
        }

        public String orderId() {
            return orderId;
        }

        public String status() {
            return status;
        }
    }

    @DomainEvent(aggregate = ShopOrder.class)
    public record OrderPlaced(@AggregateIdentifier String orderId, String amount) {
    }

    @DomainEvent(aggregate = ShopOrder.class)
    public record PaymentTaken(String paymentId, @AggregateIdentifier String orderId, String amount) {
    }

    @DomainEvent(aggregate = ShopOrder.class)
    public record StockReserved(@AggregateIdentifier String orderId) {
    }

    @DomainEvent(aggregate = ShopOrder.class)
    public record StockMissing(@AggregateIdentifier String orderId) {
    }

    /** No saga reacts to it. */
    @DomainEvent(aggregate = ShopOrder.class)
    public record OrderViewed(@AggregateIdentifier String orderId) {
    }

    @Command
    public record TakePayment(String orderId, String amount) {
    }

    @Command
    public record ReserveStock(@AggregateIdentifier String orderId) {
    }

    @Command
    public record RefundPayment(String orderId, String paymentId, String amount) {
    }

    /** The payment provider timed out: retrying may help. */
    public static final class GatewayTimeout extends RuntimeException implements TransientFailure {

        public GatewayTimeout() {
            super("payment gateway timed out");
        }
    }

    /** The card was declined for good. */
    public static final class CardDeclined extends RuntimeException implements FinalFailure {

        public CardDeclined() {
            super("card declined");
        }
    }

    @Saga
    public static final class ShopSaga {

        @SagaId
        private String sagaId;

        private SagaContext context;

        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 4,
                initialBackoffMillis = 10_000))
        public void placed(OrderPlaced event) {
            context.putState("orderId", event.orderId());
            context.putState("amount", event.amount());
            commandGateway.send(new TakePayment(event.orderId(), event.amount()));
        }

        @SagaStep(associationProperty = "orderId")
        public void paymentTaken(PaymentTaken event) {
            String amount = context.getState("amount", String.class).orElseThrow();
            if (!amount.equals(event.amount())) {
                throw new IllegalStateException("payment of " + event.amount() + " does not match " + amount);
            }
            context.putState("paymentId", event.paymentId());
            commandGateway.send(new ReserveStock(event.orderId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void stockReserved(StockReserved event) {
            // Terminal step.
        }

        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void stockMissing(StockMissing event) {
            // The trigger records why.
        }

        @SagaCompensation(forStep = PAID)
        public void refund() {
            commandGateway.send(new RefundPayment(context.getState("orderId", String.class).orElseThrow(),
                    context.getState("paymentId", String.class).orElseThrow(),
                    context.getState("amount", String.class).orElseThrow()));
        }
    }

    /** Takes and refunds payments; records the identities it saw. */
    public static final class PaymentDesk {

        private final List<CommandId> payments = new CopyOnWriteArrayList<>();
        private final List<RefundPayment> refunds = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(TakePayment command, CommandId id) {
            payments.add(id);
            DomainEvents.record(new PaymentTaken("pay-" + command.orderId(), command.orderId(), command.amount()));
        }

        @CommandHandler
        public void handle(RefundPayment command) {
            refunds.add(command);
        }

        public List<CommandId> payments() {
            return List.copyOf(payments);
        }

        public List<RefundPayment> refunds() {
            return List.copyOf(refunds);
        }
    }
}
