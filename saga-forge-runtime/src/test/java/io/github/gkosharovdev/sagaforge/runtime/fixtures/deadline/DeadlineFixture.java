package io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An engine over {@link PaymentWindowSaga} with a {@link SchedulingOutbox} and a clock the test
 * moves; {@link #advance(Duration)} delivers every due deadline as a relay would
 * (specs/017-saga-deadlines).
 */
public final class DeadlineFixture {

  public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private final MovableClock clock = new MovableClock(START);
  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
  private final SchedulingOutbox outbox = new SchedulingOutbox(boundary);
  private final StockService stock = new StockService();
  private final RecordingLogger logger = new RecordingLogger();
  private final AtomicInteger sagaIds = new AtomicInteger();
  private final AtomicCounter unitOfWorkIds = new AtomicCounter("uow-");
  private final Map<String, String> properties;
  private SagaRuntime engine;

  public DeadlineFixture() {
    this(Map.of());
  }

  public DeadlineFixture(Map<String, String> properties) {
    this.properties = Map.copyOf(properties);
    PaymentWindowSaga.RECEIVED.clear();
    engine = build();
  }

  /** A new engine over the same stores, outbox and clock, as after a process restart. */
  public DeadlineFixture restart() {
    engine = build();
    return this;
  }

  private SagaRuntime build() {
    SagaRuntime built =
        SagaRuntime.builder()
            .register(PaymentWindowSaga.class, StockService.class, Order.class)
            .storage(storage)
            .contextStore(contexts)
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(outbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(stock))
            .clock(clock)
            .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
            .unitOfWorkIds(unitOfWorkIds::next)
            .propertyResolver(key -> Optional.ofNullable(properties.get(key)))
            .logger(logger)
            .build();
    built.start();
    return built;
  }

  /** Places the order with {@code window} and reserves its stock: both deadlines are open. */
  public String placedAndReserved(String orderId, String window) {
    String sagaId = only(engine.receive(new OrderPlaced(orderId, window))).sagaId();
    only(engine.receive(new StockReserved(orderId)));
    return sagaId;
  }

  public SagaHandlingResult receive(Object event) {
    return only(engine.receive(event));
  }

  /**
   * Moves the clock and delivers every deadline due by then, earliest first; returns their results.
   */
  public List<SagaHandlingResult> advance(Duration by) {
    clock.advance(by);
    List<SagaHandlingResult> results = new ArrayList<>();
    List<ScheduledMessage> due = outbox.due(clock.instant());
    while (!due.isEmpty()) {
      ScheduledMessage next = due.get(0);
      results.add(deliver(next.messageId()));
      due = outbox.due(clock.instant());
    }
    return results;
  }

  /** Leases and delivers the scheduled message {@code id}, due or not. */
  public SagaHandlingResult deliver(String id) {
    ScheduledMessage message = outbox.lease(id);
    SagaHandlingResult result = only(engine.receive(message.payload()));
    outbox.delivered(id);
    return result;
  }

  /** Delivers {@code deadline} directly, as a stray or late message would arrive. */
  public SagaHandlingResult deliver(DeadlineReached deadline) {
    return only(engine.receive(deadline));
  }

  private static SagaHandlingResult only(HandlingReport report) {
    if (report.results().size() != 1) {
      throw new IllegalStateException("Expected one result, got " + report.results());
    }
    return report.results().get(0);
  }

  public SagaInstance committed(String sagaId) {
    return storage.committed().findById(sagaId).orElseThrow();
  }

  public SchedulingOutbox outbox() {
    return outbox;
  }

  public StockService stock() {
    return stock;
  }

  public RecordingLogger logger() {
    return logger;
  }

  public InMemorySagaContextStore contexts() {
    return contexts;
  }

  public Instant now() {
    return clock.instant();
  }

  /** A clock a test moves forward. */
  public static final class MovableClock extends Clock {

    private volatile Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class AtomicCounter {

    private final String prefix;
    private final AtomicInteger next = new AtomicInteger();

    AtomicCounter(String prefix) {
      this.prefix = prefix;
    }

    String next() {
      return prefix + next.incrementAndGet();
    }
  }
}
