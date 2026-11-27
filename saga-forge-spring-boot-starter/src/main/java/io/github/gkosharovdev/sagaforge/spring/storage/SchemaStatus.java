package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaValidationReport;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link SchemaInitializer} found at startup, shown by the {@code sagaforge} Actuator
 * endpoint.
 *
 * @param mode the configured {@code initialize-schema} mode
 * @param version the newest version in Saga Forge's history table, the detected version when there
 *     is no history, or {@code null} when neither is known
 * @param report the schema check against the current version, or {@code null} when none ran
 */
public record SchemaStatus(
    SchemaInitialization mode, SchemaVersion version, SchemaValidationReport report) {

  /** Validates the components. */
  public SchemaStatus {
    Objects.requireNonNull(mode, "mode");
  }

  /**
   * The {@code sagaforge} endpoint's {@code schema} object: {@code mode}, and when known {@code
   * version}, {@code check} ({@code PASSED} or {@code FAILED}), {@code failures} and {@code
   * warnings}.
   */
  public Map<String, Object> view() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mode", mode.name().toLowerCase(Locale.ROOT));
    if (version != null) {
      view.put("version", version.toString());
    }
    if (report != null) {
      view.put("check", report.passed() ? "PASSED" : "FAILED");
      view.put("failures", report.failures().size());
      view.put("warnings", report.warnings().size());
    }
    return view;
  }
}
