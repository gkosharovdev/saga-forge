package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes and refunds payments, idempotent by the command identity (005): a refund key seen before is not refunded
 * again. {@link #failRefundsWith} makes every refund fail, {@link #failChargesWith} every charge.
 */
public final class PaymentService {

    private final List<Long> charges = new ArrayList<>();
    private final Map<String, Refund> refunds = new LinkedHashMap<>();
    private final List<CommandId> refundIds = new ArrayList<>();
    private RuntimeException refundFailure;
    private RuntimeException chargeFailure;

    @CommandHandler
    public void handle(Charge command, CommandId commandId) {
        if (chargeFailure != null) {
            throw chargeFailure;
        }
        charges.add(command.amount());
    }

    public void failChargesWith(RuntimeException failure) {
        this.chargeFailure = failure;
    }

    @CommandHandler
    public void handle(Refund command, CommandId commandId) {
        refundIds.add(commandId);
        if (refundFailure != null) {
            throw refundFailure;
        }
        refunds.putIfAbsent(commandId.value(), command);
    }

    public void failRefundsWith(RuntimeException failure) {
        this.refundFailure = failure;
    }

    public List<Long> charges() {
        return List.copyOf(charges);
    }

    /** Refunds actually applied, one per idempotency key. */
    public List<Refund> refunds() {
        return List.copyOf(refunds.values());
    }

    /** Every refund identity the handler received, including repeats and failed ones. */
    public List<CommandId> refundIds() {
        return List.copyOf(refundIds);
    }
}
