package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.runtime.ShopRun.Vocabulary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 48 ({@code specs/016-marker-spi-jmolecules} FR-001, FR-011): for any sequence of order
 * events, including repeated starts, the canonical model marked with the test vocabulary gives the
 * same handling results, step records, statuses, outcomes, dispatched events and outcome events as
 * the Saga Forge-annotated model.
 */
class VocabularyEquivalencePropertyTest {

  /** One order and its start event. */
  record Order(String orderId, boolean inStock, String amount) {}

  @Provide
  Arbitrary<List<Order>> orders() {
    Arbitrary<Order> order =
        Combinators.combine(
                Arbitraries.of("o-1", "o-2", "o-3"),
                Arbitraries.of(true, false),
                Arbitraries.of("0", "10"))
            .as(Order::new);
    return order.list().ofMinSize(1).ofMaxSize(6);
  }

  @Property(tries = 100)
  void vocabulariesBehaveAlike(@ForAll("orders") List<Order> orders) {
    assertThat(run(Vocabulary.TEST, orders)).isEqualTo(run(Vocabulary.SAGA_FORGE, orders));
  }

  private static Map<String, String> run(Vocabulary vocabulary, List<Order> orders) {
    ShopRun run = new ShopRun(vocabulary);
    List<String> results = new ArrayList<>();
    for (Order order : orders) {
      run.seed(order.orderId(), order.inStock());
      results.addAll(run.orderCreated(order.orderId(), order.amount()));
    }
    Map<String, String> observed = new java.util.TreeMap<>(run.observed());
    observed.put("results", results.toString());
    return observed;
  }
}
