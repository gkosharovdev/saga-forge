package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.util.Objects;

/**
 * Whether a {@link UnitOfWork.Work} goes ahead against the instance its unit of work locked: {@link
 * Proceed}, or {@link Skip} with a typed reason the caller acts on. A skipped unit of work rolls
 * back and runs nothing; it is a normal outcome (a duplicate, an unmatched or terminal instance, a
 * resumed retry), not a failure.
 *
 * <p>Immutable and thread-safe.
 *
 * @param <R> what a skip reports to the caller
 */
sealed interface Admission<R> {

  /** Go ahead: load the locked instance and perform the work. */
  record Proceed<R>() implements Admission<R> {}

  /** Run nothing: roll back and report {@code reason}. */
  record Skip<R>(R reason) implements Admission<R> {

    /**
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public Skip {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Go ahead. */
  static <R> Admission<R> proceed() {
    return new Proceed<>();
  }

  /**
   * Run nothing and report {@code reason}.
   *
   * @throws NullPointerException if {@code reason} is {@code null}
   */
  static <R> Admission<R> skip(R reason) {
    return new Skip<>(reason);
  }
}
