package io.github.gkosharovdev.sagaforge.runtime;

/**
 * What the recovery sweeper does with a saga instance still {@code CREATED} after the abandonment
 * age, with no step completed: its start event never got a first unit of work to commit, and no
 * redelivery adopted it.
 */
public enum AbandonedStartPolicy {

  /**
   * Log and count it, at most once per abandonment age per instance and process, and change
   * nothing. The default: a later redelivery of the start event can still adopt the instance.
   */
  REPORT,

  /**
   * End it {@code FAILED} with a {@code SagaStartAbandonedException} failure, emitting {@code
   * SagaFailed}. A start event redelivered afterwards starts a new instance, since only an active
   * instance blocks a start.
   */
  FAIL
}
