/**
 * Outcome events: what the runtime announces when a saga instance ends (specs/011-saga-outcome-events).
 *
 * <p>Every terminal transition the runtime writes produces exactly one {@link
 * io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome}: {@link
 * io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted} for {@code COMPLETED}, {@link
 * io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed} for {@code FAILED} (phase {@code STEP}) and
 * {@code COMPENSATION_FAILED} (phase {@code COMPENSATION}), {@link
 * io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated} for {@code COMPENSATED}. The event is the last one of
 * the outgoing batch of the unit of work that stores the terminal status, so it is exactly as durable as that
 * write: an outbox that stores in the transaction delivers it at least once, after the commit.</p>
 *
 * <p>Outcome events are framework events, not domain events: they carry no {@code @DomainEvent} annotation, no
 * saga step can take one as its parameter, and the runtime reports "no saga reacted" when one is delivered to it.
 * They state facts only. They carry no failure message, no {@code SagaContext} value and no saga field, and they make
 * no judgement about whether a failure needs a person's attention: that is the observer's decision. Deduplicate them by
 * {@link io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome#eventId()}.</p>
 */
package io.github.gkosharovdev.sagaforge.core.outcome;
