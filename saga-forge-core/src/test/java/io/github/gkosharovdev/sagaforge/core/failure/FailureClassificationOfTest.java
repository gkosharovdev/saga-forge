package io.github.gkosharovdev.sagaforge.core.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FailureClassification#of(Throwable)}: the marker rule shared by engine and transports (012
 * research §3).
 */
class FailureClassificationOfTest {

  static final class Rejected extends RuntimeException implements FinalFailure {
    Rejected(Throwable cause) {
      super("rejected", cause);
    }
  }

  static final class Unavailable extends RuntimeException implements TransientFailure {
    Unavailable(Throwable cause) {
      super("unavailable", cause);
    }
  }

  static final class Both extends RuntimeException implements FinalFailure, TransientFailure {}

  static final class Looping extends RuntimeException {
    private Throwable next;

    @Override
    public synchronized Throwable getCause() {
      return next;
    }
  }

  @Test
  @DisplayName("a marker on the failure itself decides")
  void directMarker() {
    assertThat(FailureClassification.of(new Rejected(null))).isEqualTo(FailureClassification.FINAL);
    assertThat(FailureClassification.of(new Unavailable(null)))
        .isEqualTo(FailureClassification.TRANSIENT);
  }

  @Test
  @DisplayName("a marker further down the cause chain decides")
  void nestedMarker() {
    RuntimeException wrapped =
        new IllegalStateException("outer", new RuntimeException(new Rejected(null)));

    assertThat(FailureClassification.of(wrapped)).isEqualTo(FailureClassification.FINAL);
  }

  @Test
  @DisplayName("the first marker along the chain wins, and one that is both is final")
  void firstMarkerWins() {
    assertThat(FailureClassification.of(new Unavailable(new Rejected(null))))
        .isEqualTo(FailureClassification.TRANSIENT);
    assertThat(FailureClassification.of(new Rejected(new Unavailable(null))))
        .isEqualTo(FailureClassification.FINAL);
    assertThat(FailureClassification.of(new Both())).isEqualTo(FailureClassification.FINAL);
  }

  @Test
  @DisplayName("an unmarked failure is unclassified, also when its cause chain loops")
  void unmarkedAndCycle() {
    Looping first = new Looping();
    Looping second = new Looping();
    first.next = second;
    second.next = first;

    assertThat(FailureClassification.of(new IllegalStateException("plain")))
        .isEqualTo(FailureClassification.UNCLASSIFIED);
    assertThat(FailureClassification.of(first)).isEqualTo(FailureClassification.UNCLASSIFIED);
  }

  @Test
  @DisplayName("an Error is unclassified even with a marked cause")
  void error() {
    assertThat(FailureClassification.of(new AssertionError("bug", new Rejected(null))))
        .isEqualTo(FailureClassification.UNCLASSIFIED);
  }

  @Test
  @DisplayName("null is rejected")
  void nullRejected() {
    assertThatThrownBy(() -> FailureClassification.of(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failure");
  }
}
