package io.github.gkosharovdev.sagaforge.core.operations;

import java.io.Serial;

/**
 * The cause recorded when recovery ends an abandoned start {@code FAILED}: a saga instance created
 * but whose first unit of work never committed, and whose start event was not delivered again
 * within the abandonment age. Its class name is the failure's cause type; it is never thrown to a
 * caller.
 */
public final class SagaStartAbandonedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * @param message what was abandoned and for how long
   */
  public SagaStartAbandonedException(String message) {
    super(message);
  }
}
