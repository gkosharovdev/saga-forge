package io.github.gkosharovdev.sagaforge.core.deadline;

/** Component checks for this package's records. */
final class Checks {

  private Checks() {}

  static String requireText(String value, String component) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(component + " must not be blank");
    }
    return value;
  }

  static <T> T requireNonNull(T value, String component) {
    if (value == null) {
      throw new IllegalArgumentException(component + " must not be null");
    }
    return value;
  }
}
