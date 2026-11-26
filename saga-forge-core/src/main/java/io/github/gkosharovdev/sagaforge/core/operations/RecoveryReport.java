package io.github.gkosharovdev.sagaforge.core.operations;

/**
 * What one recovery sweep did.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a count is negative.
 *
 * @param compensationsResumed stalled compensating instances resumed
 * @param startsReported abandoned starts reported for an operator
 * @param startsFailed abandoned starts ended {@code FAILED}
 * @param errors instances the sweep could not handle; each was logged
 */
public record RecoveryReport(
    int compensationsResumed, int startsReported, int startsFailed, int errors) {

  public RecoveryReport {
    requireNotNegative(compensationsResumed, "compensationsResumed");
    requireNotNegative(startsReported, "startsReported");
    requireNotNegative(startsFailed, "startsFailed");
    requireNotNegative(errors, "errors");
  }

  /**
   * @return a sweep that did nothing
   */
  public static RecoveryReport none() {
    return new RecoveryReport(0, 0, 0, 0);
  }

  private static void requireNotNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative, but is " + value);
    }
  }
}
