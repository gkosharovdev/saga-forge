/**
 * Booking reference model for spec 002 User Stories 2 and 3: {@link
 * io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga CreateBookingSaga} and the
 * aggregates, handlers, commands, and events it drives. Every association uses {@code bookingId}, which every
 * event carries. The saga's outcomes are its two {@code @EndSaga} steps, {@code CreateBookingSaga.OUTCOME_CONFIRMED}
 * ({@code onBookingConfirmed}) and {@code CreateBookingSaga.OUTCOME_DECLINED} ({@code onPaymentDeclined}).
 *
 * <h2>Flow</h2>
 * <ul>
 *   <li>{@code BookingRequestedEvent} (start; published from outside any unit of work) →
 *       {@code ClaimAvailability} → {@code AvailabilityClaimedEvent} → {@code InitiatePayment} →
 *       {@code PaymentInitiatedEvent} (no step reacts; the first unit of work commits and the saga waits).</li>
 *   <li>Outcome "confirmed": {@code PaymentConfirmedEvent} → {@code ConfirmBooking} →
 *       {@code BookingConfirmedEvent}.</li>
 *   <li>Outcome "declined": {@code PaymentDeclinedEvent} → {@code ReleaseAvailability} →
 *       {@code AvailabilityReleasedEvent} (dispatched after commit; no step reacts).</li>
 *   <li>{@code PaymentConfirmedEvent} and {@code PaymentDeclinedEvent} come from "the payment provider" through
 *       the transport. No fixture records them; tests deliver them.</li>
 * </ul>
 *
 * <h2>Handler kinds</h2>
 * <table>
 *   <caption>Which handler kind handles each command</caption>
 *   <tr><th>Command</th><th>Handler</th><th>Kind</th></tr>
 *   <tr><td>{@code ClaimAvailability}, {@code ReleaseAvailability}</td><td>{@code Inventory#handle}</td>
 *       <td>Instance method of a plain {@code @Aggregate}; loaded by the command's {@code @AggregateIdentifier}
 *       through {@code InventoryRepository#load} ({@code @AggregateLoader}, returns {@code null} when absent,
 *       which the gateway reports as {@code AggregateNotFoundException}) and saved through
 *       {@code InventoryRepository#save} ({@code @AggregateSaver}).</td></tr>
 *   <tr><td>{@code ConfirmBooking}</td><td>{@code Booking(ConfirmBooking)}</td>
 *       <td>Creation constructor of an {@code @Aggregate}; the command has no {@code @AggregateIdentifier}. The
 *       new aggregate is saved through {@code BookingRepository#save}.</td></tr>
 *   <tr><td>{@code InitiatePayment}</td><td>{@code PaymentService#handle}</td>
 *       <td>Service-hosted; changes the marker aggregate {@code Payment}.</td></tr>
 * </table>
 *
 * <p>{@code BookingRequestedEvent} names {@code Booking} as its aggregate although the {@code Booking} is only
 * created by {@code ConfirmBooking}: naming an aggregate type does not require an instance, and an externally
 * published event is never a step's touched aggregate. Derived ids: {@code Inventory.claimIdFor(bookingId)}
 * ({@code "claim-" + bookingId}) and {@code PaymentService.paymentIdFor(bookingId)}
 * ({@code "payment-" + bookingId}).</p>
 *
 * <h2>Fault injection</h2>
 * <p>{@code failNext()} / {@code failNext(RuntimeException)} arm a one-shot failure that fires after the
 * handler's state change and before it records its event, then disarms. It is never part of a snapshot.</p>
 * <ul>
 *   <li>{@code Inventory}: per instance; arm the instance returned by {@code InventoryRepository#load}.</li>
 *   <li>{@code PaymentService}: per service instance; {@code reset()} disarms.</li>
 *   <li>{@code Booking}: static and thread-local, because a constructor has no instance to arm beforehand;
 *       {@code Booking.resetFaults()} disarms.</li>
 * </ul>
 *
 * <h2>Snapshots and save counts</h2>
 * <p>{@code InventoryRepository}, {@code BookingRepository}, and {@code PaymentService} implement the test-only
 * {@link io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable Snapshottable}. The
 * inventory snapshot deep-copies every {@code Inventory} via {@code Inventory#copy()}, and {@code restore}
 * installs fresh copies, so re-read inventories after a rollback. Both repositories count {@code save} calls in
 * {@code saveCount()}; the count is a call counter, not state, and is not restored by a rollback.</p>
 */
package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;
