package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@link SagaStep} that runs when the named deadline expires ({@code
 * specs/017-saga-deadlines} FR-010). Every deadline a saga starts has exactly one such step (rule
 * R24).
 *
 * <p>The step takes no parameter or one {@code core.deadline.DeadlineReached}, and its {@link
 * SagaStep#associationProperty()} is left empty: a deadline is addressed to its instance directly
 * (rule R27). It is never {@link StartSaga @StartSaga} (rule R28). It is typically also {@link
 * StartCompensation @StartCompensation}: the instance then compensates, with a compensation trigger
 * of kind {@code DEADLINE_EXPIRED}. It may also be {@link EndSaga @EndSaga}, or an ordinary step
 * that keeps the instance running, for example a reminder that starts its own deadline again (rule
 * R29).
 *
 * <p>It runs only while the instance is {@code RUNNING} and the deadline is still open; a deadline
 * delivered later is ignored.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DeadlineExpired {

  /**
   * @return the name of the deadline this step handles
   */
  String value();
}
