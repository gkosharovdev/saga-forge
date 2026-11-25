/**
 * Saga deadlines ({@code specs/017-saga-deadlines}): the value a deadline delivers when it expires
 * ({@link io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached}), the open deadline an instance keeps
 * ({@link io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline}) and the failure of a duration read from the
 * saga's context ({@link io.github.gkosharovdev.sagaforge.core.deadline.DeadlineDurationException}).
 *
 * <p>Deadlines are declared with {@code @StartDeadline} on the step that opens one and {@code @DeadlineExpired} on the
 * step that runs when it expires.</p>
 */
package io.github.gkosharovdev.sagaforge.core.deadline;
