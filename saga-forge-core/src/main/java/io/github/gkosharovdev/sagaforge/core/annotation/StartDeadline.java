package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Starts a named deadline when the annotated step's unit of work commits ({@code
 * specs/017-saga-deadlines} FR-009). Placed on a {@link SagaStep} method, at most once per deadline
 * name per saga (rule R24). When the deadline comes due while the instance is still running and the
 * deadline still open, the step marked {@link DeadlineExpired @DeadlineExpired} with the same name
 * runs.
 *
 * <p>The duration is either fixed ({@link #duration()}: an ISO-8601 duration such as {@code
 * "PT15M"}, or a property placeholder {@code "${key}"} / {@code "${key:PT15M}"} resolved once at
 * startup) or read from the saga's context ({@link #durationFrom()}: the name of a {@code
 * SagaContext} attribute holding an ISO-8601 {@code String}, read after the step's method
 * returned). Exactly one of the two is given (rule R25). The deadline is due at the time the step
 * completed plus the duration; a zero or negative duration is due at once.
 *
 * <p>The deadline is cancelled when a step named in {@link #cancelledBy()} completes, when it is
 * started again (it is then replaced), and whenever the instance stops running: it completes,
 * starts compensating or fails.
 *
 * <p>Deadlines need an {@code IN_TRANSACTION} outbox that can schedule messages, such as the
 * in-memory transport over a scheduling message store; the runtime refuses to start otherwise.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(StartDeadlines.class)
public @interface StartDeadline {

  /**
   * @return the deadline's name; a step starts a name at most once, several steps may start the
   *     same deadline
   */
  String name();

  /**
   * @return a fixed ISO-8601 duration or a {@code ${key[:default]}} placeholder; empty when {@link
   *     #durationFrom()} is used
   */
  String duration() default "";

  /**
   * @return the {@code SagaContext} attribute holding the duration as an ISO-8601 {@code String};
   *     empty when {@link #duration()} is used
   */
  String durationFrom() default "";

  /**
   * @return the steps whose completion cancels the deadline; each names a {@link SagaStep} method
   *     of the same saga (rule R26)
   */
  String[] cancelledBy() default {};
}
