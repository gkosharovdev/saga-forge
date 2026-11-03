package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * Reference saga for spec 002 User Story 3: a long-running saga that waits between units of work and reaches
 * one of two outcomes. Every step correlates on {@code bookingId}.
 *
 * <h2>First unit of work (ends at a wait point)</h2>
 * <ol>
 *   <li>{@code @StartSaga} {@link #onBookingRequested(BookingRequestedEvent)}: writes
 *       {@value #CONTEXT_INVENTORY_ID} and {@value #CONTEXT_AMOUNT} into {@link SagaContext} and sends
 *       {@link ClaimAvailability} ({@link Inventory} records {@link AvailabilityClaimedEvent}).</li>
 *   <li>{@link #onAvailabilityClaimed(AvailabilityClaimedEvent)}: writes {@value #CONTEXT_CLAIM_ID}, reads
 *       {@value #CONTEXT_AMOUNT}, and sends {@link InitiatePayment} ({@link PaymentService} records
 *       {@link PaymentInitiatedEvent}).</li>
 *   <li>No step reacts to {@link PaymentInitiatedEvent}: the chain stops, the first unit of work commits, and
 *       the saga waits {@code RUNNING} for the payment provider's callback.</li>
 * </ol>
 *
 * <h2>Outcome "confirmed" ({@value #OUTCOME_CONFIRMED})</h2>
 * <ol>
 *   <li>{@link #onPaymentConfirmed(PaymentConfirmedEvent)} (delivered through the transport): writes
 *       {@value #CONTEXT_PAYMENT_ID}, reads {@value #CONTEXT_CLAIM_ID}, and sends {@link ConfirmBooking}
 *       (the {@link Booking} creation handler records {@link BookingConfirmedEvent}).</li>
 *   <li>{@code @EndSaga} {@link #onBookingConfirmed(BookingConfirmedEvent)}: the outcome; dispatches
 *       nothing.</li>
 * </ol>
 *
 * <h2>Outcome "declined" ({@value #OUTCOME_DECLINED})</h2>
 * <ol>
 *   <li>{@code @EndSaga} {@link #onPaymentDeclined(PaymentDeclinedEvent)} (delivered through the transport):
 *       reads {@value #CONTEXT_INVENTORY_ID} and {@value #CONTEXT_CLAIM_ID} from {@link SagaContext} and sends
 *       {@link ReleaseAvailability} ({@link Inventory} records {@link AvailabilityReleasedEvent}, which no step
 *       reacts to).</li>
 * </ol>
 *
 * <p>On either path the steps of the other path stay not run. A missing {@link SagaContext} value is a
 * programming error in this fixture and fails the step with {@link IllegalStateException}.</p>
 */
@Saga
public class CreateBookingSaga {

    /** Name of the {@link SagaContext} value holding the {@link Inventory} id, written by the start step. */
    public static final String CONTEXT_INVENTORY_ID = "inventoryId";

    /** Name of the {@link SagaContext} value holding the amount to charge, written by the start step. */
    public static final String CONTEXT_AMOUNT = "amount";

    /** Name of the {@link SagaContext} value holding the availability claim id, written by
     *  {@value #AVAILABILITY_CLAIMED_STEP}. */
    public static final String CONTEXT_CLAIM_ID = "claimId";

    /** Name of the {@link SagaContext} value holding the confirmed payment id, written by
     *  {@value #PAYMENT_CONFIRMED_STEP}. */
    public static final String CONTEXT_PAYMENT_ID = "paymentId";

    public static final String START_STEP = "onBookingRequested";
    public static final String AVAILABILITY_CLAIMED_STEP = "onAvailabilityClaimed";
    public static final String PAYMENT_CONFIRMED_STEP = "onPaymentConfirmed";
    public static final String BOOKING_CONFIRMED_STEP = "onBookingConfirmed";
    public static final String PAYMENT_DECLINED_STEP = "onPaymentDeclined";

    /** Outcome reached when the payment is confirmed: the {@code @EndSaga} step {@value}. */
    public static final String OUTCOME_CONFIRMED = BOOKING_CONFIRMED_STEP;

    /** Outcome reached when the payment is declined: the {@code @EndSaga} step {@value}. */
    public static final String OUTCOME_DECLINED = PAYMENT_DECLINED_STEP;

    /** Steps run by the first unit of work, before the wait point. */
    public static final List<String> FIRST_UNIT_STEPS = List.of(START_STEP, AVAILABILITY_CLAIMED_STEP);

    /** Steps run by the second unit of work on the "confirmed" path. */
    public static final List<String> CONFIRMED_PATH_STEPS = List.of(PAYMENT_CONFIRMED_STEP, BOOKING_CONFIRMED_STEP);

    /** Steps run by the second unit of work on the "declined" path. */
    public static final List<String> DECLINED_PATH_STEPS = List.of(PAYMENT_DECLINED_STEP);

    /** Every step method name. */
    public static final List<String> ALL_STEPS = List.of(
            START_STEP, AVAILABILITY_CLAIMED_STEP, PAYMENT_CONFIRMED_STEP, BOOKING_CONFIRMED_STEP,
            PAYMENT_DECLINED_STEP);

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /** Starts the saga: remembers the inventory and amount, then claims availability. */
    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBookingRequested(BookingRequestedEvent event) {
        context.putState(CONTEXT_INVENTORY_ID, event.inventoryId());
        context.putState(CONTEXT_AMOUNT, event.amount());
        commandGateway.send(new ClaimAvailability(
                event.inventoryId(), event.bookingId(), event.checkIn(), event.checkOut()));
    }

    /** Remembers the claim and initiates payment of the amount the start step stored. */
    @SagaStep(associationProperty = "bookingId")
    public void onAvailabilityClaimed(AvailabilityClaimedEvent event) {
        context.putState(CONTEXT_CLAIM_ID, event.claimId());
        long amount = require(CONTEXT_AMOUNT, Long.class, START_STEP);
        commandGateway.send(new InitiatePayment(event.bookingId(), amount));
    }

    /** Payment confirmed by the provider: remembers the payment and confirms the booking against the claim. */
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        context.putState(CONTEXT_PAYMENT_ID, event.paymentId());
        String claimId = require(CONTEXT_CLAIM_ID, String.class, AVAILABILITY_CLAIMED_STEP);
        commandGateway.send(new ConfirmBooking(event.bookingId(), claimId, event.paymentId()));
    }

    /** Outcome "confirmed": the booking exists and holds the claim and the payment. */
    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        // Outcome step: nothing left to dispatch.
    }

    /** Outcome "declined": releases the claimed availability, read back from {@link SagaContext}. */
    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        String inventoryId = require(CONTEXT_INVENTORY_ID, String.class, START_STEP);
        String claimId = require(CONTEXT_CLAIM_ID, String.class, AVAILABILITY_CLAIMED_STEP);
        commandGateway.send(new ReleaseAvailability(inventoryId, event.bookingId(), claimId));
    }

    private <T> T require(String name, Class<T> type, String writtenBy) {
        return context.getState(name, type)
                .orElseThrow(() -> new IllegalStateException(
                        "SagaContext value '" + name + "' written by " + writtenBy + " is missing"));
    }
}
