package io.github.gkosharovdev.sagaforge.core.operations;

/**
 * Who asks for a repair, and why. Every repair action takes one; the runtime records both, with the
 * time, in the instance's lifecycle history. The runtime never looks the operator up itself: the
 * caller of the operations API knows whom it acts for (an HTTP endpoint takes the authenticated
 * user).
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code operator} or
 * {@code reason} is {@code null}, blank or too long.
 *
 * @param operator the person or system acting; non-blank, at most 200 characters
 * @param reason why the action is taken; non-blank, at most 2,000 characters
 */
public record OperatorRequest(String operator, String reason) {

  /** The operator the runtime's own recovery acts as. */
  public static final String RECOVERY_OPERATOR = "saga-forge-recovery";

  /** The longest accepted {@link #operator()}. */
  public static final int MAX_OPERATOR_LENGTH = 200;

  /** The longest accepted {@link #reason()}. */
  public static final int MAX_REASON_LENGTH = 2_000;

  public OperatorRequest {
    Checks.nonBlank(operator, "operator", MAX_OPERATOR_LENGTH);
    Checks.nonBlank(reason, "reason", MAX_REASON_LENGTH);
  }

  /**
   * @return a request by {@link #RECOVERY_OPERATOR} with {@code reason}
   */
  public static OperatorRequest recovery(String reason) {
    return new OperatorRequest(RECOVERY_OPERATOR, reason);
  }
}
