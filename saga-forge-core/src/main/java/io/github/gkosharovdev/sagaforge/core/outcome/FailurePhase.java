package io.github.gkosharovdev.sagaforge.core.outcome;

/** Where a saga instance failed: in a step, or in the compensation of a step. */
public enum FailurePhase {

  /** A step failed and the instance ended {@code FAILED}. */
  STEP,

  /** A compensation failed and the instance ended {@code COMPENSATION_FAILED}. */
  COMPENSATION
}
