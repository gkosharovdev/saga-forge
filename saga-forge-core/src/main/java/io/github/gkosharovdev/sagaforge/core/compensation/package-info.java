/**
 * Compensation vocabulary ({@code specs/006-saga-compensation}).
 *
 * <ul>
 *   <li>{@link CompensationTrigger} — why an instance entered compensation: a {@code @StartCompensation} step (a
 *       business failure), a final step failure, or exhausted retries. Recorded once on the instance and passed to a
 *       {@code @SagaCompensation} method that declares a parameter of this type.</li>
 *   <li>{@link CompensationTriggerKind} — which of the three it was.</li>
 * </ul>
 *
 * <p>Like the rest of {@code saga-forge-core}, this package has zero dependencies.</p>
 */
package io.github.gkosharovdev.sagaforge.core.compensation;
