/**
 * In-process reference model for spec 002 User Story 1: a saga whose whole path runs in one saga-scoped
 * unit of work.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li>{@link io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga ThreeStepSaga}:
 *       {@code OrderPlaced -> ItemsReserved -> InvoiceIssued -> ShipmentScheduled}. Three steps dispatch a
 *       command; the fourth is the {@code @EndSaga} outcome step. Its methods are declared in a different
 *       order from their events (spec US1 scenario 5).</li>
 *   <li>Aggregates (markers, no handlers): {@code Order} (named by the externally published start event
 *       only; never changed in the unit of work), {@code Reservation}, {@code Invoice}, {@code Shipment}
 *       (the three aggregates the unit of work changes).</li>
 *   <li>Service-hosted handlers (plain classes, no Saga Forge supertype): {@code ReservationService}
 *       ({@code ReserveItems -> ItemsReserved}), {@code InvoiceService} ({@code IssueInvoice -> InvoiceIssued}),
 *       {@code ShipmentService} ({@code ScheduleShipment -> ShipmentScheduled}). Each records its event with
 *       {@code DomainEvents.record}. Aggregate ids are derived from the order id
 *       ({@code reservationIdFor}, {@code invoiceIdFor}, {@code shipmentIdFor}).</li>
 * </ul>
 *
 * <h2>Fault injection</h2>
 * <ul>
 *   <li>Handlers: {@code failNext()} / {@code failNext(RuntimeException)} arm a one-shot failure. The next
 *       {@code handle} call first applies its aggregate change, then throws (before recording its event),
 *       so a rollback has a partial change to undo. The switch disarms after firing and is not part of the
 *       snapshot.</li>
 *   <li>Saga steps: {@link io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe
 *       ThreeStepSagaProbe#failStep(String)} arms a one-shot failure for a named step, which throws after the
 *       step did its work (after its {@code send}, if any). This is the only way to fail the {@code @EndSaga}
 *       step, which dispatches nothing. The probe also traces the order in which steps were entered. Its
 *       state is thread-local; call {@code ThreeStepSagaProbe.reset()} before each test.</li>
 * </ul>
 *
 * <h2>Snapshot approach</h2>
 * <p>Each handler service implements the test-only
 * {@link io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable Snapshottable}
 * interface defined in this package ({@code Object snapshot()} / {@code void restore(Object)}). A harness
 * takes a snapshot of every {@code Snapshottable} handler instance when a unit of work begins and restores
 * it on rollback. Snapshots are opaque, immutable copies of the "changed aggregates" state, and
 * {@code restore} rejects a snapshot taken from a different class. {@code changedAggregates()} on each
 * service returns the ids of the aggregates it changed, in change order, for assertions.</p>
 */
package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;
