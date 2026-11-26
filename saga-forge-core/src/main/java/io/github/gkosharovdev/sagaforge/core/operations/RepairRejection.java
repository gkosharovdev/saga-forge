package io.github.gkosharovdev.sagaforge.core.operations;

/** Why a repair action did not apply. */
public enum RepairRejection {
  /** No saga instance has the id. */
  NOT_FOUND,
  /** The instance's status does not admit the action. */
  WRONG_STATUS,
  /** The instance was resolved; it accepts no further repair. */
  ALREADY_RESOLVED
}
