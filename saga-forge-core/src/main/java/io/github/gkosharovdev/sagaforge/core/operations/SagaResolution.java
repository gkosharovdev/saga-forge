package io.github.gkosharovdev.sagaforge.core.operations;

import java.time.Instant;

/**
 * An operator's statement that the remaining work of a {@code FAILED} or {@code
 * COMPENSATION_FAILED} saga instance was handled outside the saga. A resolved instance keeps its
 * status, accepts no further repair and no longer counts as needing attention.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code
 * null} or a text is blank.
 *
 * @param operator who resolved it; non-blank
 * @param reason why; non-blank
 * @param resolvedAt when; non-null
 */
public record SagaResolution(String operator, String reason, Instant resolvedAt) {

  public SagaResolution {
    Checks.nonBlank(operator, "operator", OperatorRequest.MAX_OPERATOR_LENGTH);
    Checks.nonBlank(reason, "reason", OperatorRequest.MAX_REASON_LENGTH);
    Checks.nonNull(resolvedAt, "resolvedAt");
  }

  /**
   * @return the resolution {@code request} asks for, made at {@code at}
   */
  public static SagaResolution of(OperatorRequest request, Instant at) {
    Checks.nonNull(request, "request");
    return new SagaResolution(request.operator(), request.reason(), at);
  }
}
