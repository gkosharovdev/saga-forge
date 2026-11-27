package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.runtime.ShopRun.Vocabulary;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A third vocabulary runs the canonical saga with no change to Saga Forge's modules ({@code
 * specs/016-marker-spi-jmolecules} US4, SC-007): the model marked with the test vocabulary
 * completes, compensates and fails exactly as the Saga Forge-annotated one.
 */
class ThirdVocabularyTest {

  record Observed(List<String> results, Map<String, String> state) {}

  static Stream<Arguments> scenarios() {
    return Stream.of(
        Arguments.of("completes", true, "10", "COMPLETED"),
        Arguments.of("compensates", false, "10", "COMPENSATED"),
        Arguments.of("fails", true, "0", "FAILED"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void behavesAsSagaForgeAnnotations(
      String name, boolean inStock, String amount, String expectedOutcome) {
    Observed annotated = run(Vocabulary.SAGA_FORGE, inStock, amount);
    Observed marked = run(Vocabulary.TEST, inStock, amount);

    assertThat(marked).isEqualTo(annotated);
    assertThat(marked.results()).singleElement().asString().contains(" " + expectedOutcome + " ");
  }

  private static Observed run(Vocabulary vocabulary, boolean inStock, String amount) {
    ShopRun run = new ShopRun(vocabulary);
    run.seed("o-1", inStock);
    List<String> results = run.orderCreated("o-1", amount);
    return new Observed(results, run.observed());
  }
}
