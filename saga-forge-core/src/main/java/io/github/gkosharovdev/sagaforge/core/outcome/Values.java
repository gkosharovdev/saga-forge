package io.github.gkosharovdev.sagaforge.core.outcome;

import java.util.LinkedHashMap;
import java.util.Map;

/** Argument checks shared by the outcome records. */
final class Values {

  private Values() {}

  static String requireText(String value, String component) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(component + " must not be null or blank");
    }
    return value;
  }

  static <T> T requireNonNull(T value, String component) {
    if (value == null) {
      throw new IllegalArgumentException(component + " must not be null");
    }
    return value;
  }

  static Map<String, String> associations(Map<String, String> associations) {
    if (associations == null || associations.isEmpty()) {
      throw new IllegalArgumentException("associations must not be null or empty");
    }
    Map<String, String> copy = new LinkedHashMap<>();
    associations.forEach(
        (property, value) ->
            copy.put(
                requireText(property, "associations key"),
                requireText(value, "associations value of " + property)));
    return java.util.Collections.unmodifiableMap(copy);
  }

  static String eventId(String sagaId, String status) {
    return eventId(sagaId, status, 0);
  }

  /**
   * {@code sagaId:STATUS} in compensation round 0, else {@code sagaId:STATUS:r<round>}, so every
   * terminal write of one instance has its own id ({@code specs/015-saga-operations} research R11).
   */
  static String eventId(String sagaId, String status, int compensationRound) {
    if (compensationRound < 0) {
      throw new IllegalArgumentException(
          "compensationRound must not be negative, but is " + compensationRound);
    }
    return compensationRound == 0
        ? sagaId + ":" + status
        : sagaId + ":" + status + ":r" + compensationRound;
  }
}
