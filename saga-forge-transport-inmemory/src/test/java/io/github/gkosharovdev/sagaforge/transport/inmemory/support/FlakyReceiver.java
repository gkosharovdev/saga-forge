package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link EventReceiver} that fails deliveries of chosen types a set number of times, then
 * delegates.
 */
public final class FlakyReceiver implements EventReceiver {

  private final EventReceiver delegate;
  private final Map<Class<?>, AtomicInteger> failures = new ConcurrentHashMap<>();

  public FlakyReceiver(EventReceiver delegate) {
    this.delegate = delegate;
  }

  public void fail(Class<?> type, int times) {
    failures.put(type, new AtomicInteger(times));
  }

  @Override
  public HandlingReport receive(Object domainEvent) {
    failIfChosen(domainEvent);
    return delegate.receive(domainEvent);
  }

  /** Forwards the source (014), so the engine still leaves out the instance that ran the event. */
  @Override
  public HandlingReport receive(Object domainEvent, String sourceSagaId) {
    failIfChosen(domainEvent);
    return delegate.receive(domainEvent, sourceSagaId);
  }

  private void failIfChosen(Object domainEvent) {
    AtomicInteger left = failures.get(domainEvent.getClass());
    if (left != null && left.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new IllegalStateException("injected delivery failure of " + domainEvent);
    }
  }
}
