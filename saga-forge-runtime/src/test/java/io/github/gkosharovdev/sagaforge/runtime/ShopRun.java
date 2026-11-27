package io.github.gkosharovdev.sagaforge.runtime;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.vocabulary.AnnotatedShop;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.vocabulary.MarkedShop;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One hand-assembled runtime over {@link AnnotatedShop} or {@link MarkedShop}, observed as text
 * with the model's class names removed, so the two vocabularies' runs compare equal when they
 * behave equally ({@code specs/016-marker-spi-jmolecules} US4, Property 48).
 */
final class ShopRun {

  /** Which twin of the canonical model, and so which vocabulary, the run uses. */
  enum Vocabulary {
    SAGA_FORGE,
    TEST
  }

  private final Vocabulary vocabulary;
  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
  private final AnnotatedShop.OrderRepository annotatedOrders = new AnnotatedShop.OrderRepository();
  private final MarkedShop.OrderRepository markedOrders = new MarkedShop.OrderRepository();
  private final AtomicInteger sagaIds = new AtomicInteger();
  private final AtomicInteger unitOfWorkIds = new AtomicInteger();
  private final SagaRuntime runtime;

  ShopRun(Vocabulary vocabulary) {
    this.vocabulary = vocabulary;
    SagaRuntime.Builder builder =
        SagaRuntime.builder()
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(dispatcher)
            .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
            .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
            .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet());
    if (vocabulary == Vocabulary.SAGA_FORGE) {
      builder
          .register(
              AnnotatedShop.OrderProcessingSaga.class,
              AnnotatedShop.Order.class,
              AnnotatedShop.OrderRepository.class,
              AnnotatedShop.PaymentService.class)
          .handlerInstances(
              new MapHandlerInstanceProvider()
                  .register(annotatedOrders)
                  .register(new AnnotatedShop.PaymentService()));
    } else {
      builder
          .domainTypes(DomainTypes.of(new TestDomainTypeResolver()))
          .register(
              MarkedShop.OrderProcessingSaga.class,
              MarkedShop.Order.class,
              MarkedShop.OrderRepository.class,
              MarkedShop.PaymentService.class)
          .handlerInstances(
              new MapHandlerInstanceProvider()
                  .register(markedOrders)
                  .register(new MarkedShop.PaymentService()));
    }
    runtime = builder.build();
    runtime.start();
  }

  /** Stores an order before any event concerns it. */
  void seed(String orderId, boolean inStock) {
    if (vocabulary == Vocabulary.SAGA_FORGE) {
      annotatedOrders.save(new AnnotatedShop.Order(orderId, inStock));
    } else {
      markedOrders.save(new MarkedShop.Order(orderId, inStock));
    }
  }

  /** Delivers the start event and returns the handling results, as text. */
  List<String> orderCreated(String orderId, String amount) {
    Object event =
        vocabulary == Vocabulary.SAGA_FORGE
            ? new AnnotatedShop.OrderCreated(orderId, amount)
            : new MarkedShop.OrderCreated(orderId, amount);
    return runtime.receive(event).results().stream().map(ShopRun::describe).toList();
  }

  /** Every stored instance and every dispatched event, as text. */
  Map<String, String> observed() {
    Map<String, String> observed = new TreeMap<>();
    storage.committed().all().stream()
        .sorted(Comparator.comparing(SagaInstance::sagaId))
        .forEach(instance -> observed.put("instance " + instance.sagaId(), describe(instance)));
    observed.put("dispatched", normalized(dispatcher.dispatched().toString()));
    return observed;
  }

  private static String describe(SagaHandlingResult result) {
    return normalized(
        result.sagaType()
            + " "
            + result.outcome()
            + " started="
            + result.started()
            + " outcome="
            + result.outcomeStep());
  }

  private static String describe(SagaInstance instance) {
    Map<String, String> steps = new TreeMap<>();
    instance
        .steps()
        .forEach(
            (step, record) ->
                steps.put(
                    step,
                    record.status()
                        + "x"
                        + record.attempts()
                        + (record.compensation() == null
                            ? ""
                            : " " + record.compensation().status())));
    return normalized(
        instance.sagaType()
            + " "
            + instance.status()
            + " outcome="
            + instance.outcome()
            + " associations="
            + new TreeMap<>(instance.associations())
            + " steps="
            + steps
            + " failure="
            + (instance.failure() == null ? null : instance.failure().stepName()));
  }

  private static String normalized(String text) {
    return text.replace(AnnotatedShop.class.getName(), "Shop")
        .replace(MarkedShop.class.getName(), "Shop");
  }
}
