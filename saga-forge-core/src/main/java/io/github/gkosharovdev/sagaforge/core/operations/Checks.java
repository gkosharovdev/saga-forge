package io.github.gkosharovdev.sagaforge.core.operations;

/** Argument checks shared by the operations records. */
final class Checks {

  private Checks() {}

  static <T> T nonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  static String nonBlank(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          name + " must be at most " + maxLength + " characters, but has " + value.length());
    }
    return value;
  }
}
