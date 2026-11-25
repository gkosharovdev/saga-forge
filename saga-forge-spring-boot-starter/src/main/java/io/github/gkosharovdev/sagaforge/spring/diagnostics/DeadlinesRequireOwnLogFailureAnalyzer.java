package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Explains a saga model refused because its deadlines need scheduling while Spring Modulith interop
 * gives the transport the Modulith event publication registry as its message store, which cannot
 * schedule ({@code specs/017-saga-deadlines} contracts/outbox-and-gateway.md). Any other refusal is
 * left to {@link SagaRuntimeStartupFailureAnalyzer}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DeadlinesRequireOwnLogFailureAnalyzer
    extends AbstractFailureAnalyzer<SagaRuntimeStartupException> {

  static final String MODULITH_REGISTRY =
      "org.springframework.modulith.events.core.EventPublicationRepository";

  private final Environment environment;

  /**
   * @param environment the application's environment, read for {@code
   *     saga-forge.transport.modulith-interop}
   */
  public DeadlinesRequireOwnLogFailureAnalyzer(Environment environment) {
    this.environment = environment;
  }

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, SagaRuntimeStartupException cause) {
    List<String> errors = cause.report().allErrors();
    if (!interopActive()
        || errors.stream().noneMatch(error -> error.contains("cannot schedule messages"))) {
      return null;
    }
    String listed =
        errors.stream()
            .map(error -> "    - " + error)
            .collect(Collectors.joining(System.lineSeparator()));
    return new FailureAnalysis(
        "The saga model was refused at startup:"
            + System.lineSeparator()
            + listed
            + System.lineSeparator()
            + "Spring Modulith interop is on: the transport stores its messages in the"
            + " Modulith event publication registry, which cannot hold a message until a due time.",
        "Set saga-forge.transport.modulith-interop=false to keep Saga Forge's own message log.",
        cause);
  }

  private boolean interopActive() {
    return environment.getProperty("saga-forge.transport.modulith-interop", Boolean.class, true)
        && ClassUtils.isPresent(MODULITH_REGISTRY, getClass().getClassLoader());
  }
}
