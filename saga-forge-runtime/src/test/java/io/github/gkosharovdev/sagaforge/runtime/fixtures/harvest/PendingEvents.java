package io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Parcel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double of {@link AggregateEventSource}: takes a {@link Parcel}'s pending events, records the
 * types it was asked about, and can throw or add a foreign object once.
 */
public final class PendingEvents implements AggregateEventSource {

  private final boolean supportsEverything;
  private final List<Class<?>> asked = new CopyOnWriteArrayList<>();
  private RuntimeException failure;
  private Object extra;

  private PendingEvents(boolean supportsEverything) {
    this.supportsEverything = supportsEverything;
  }

  /** A source for {@link Parcel}s only. */
  public static PendingEvents forParcels() {
    return new PendingEvents(false);
  }

  /** A source that claims every type and returns nothing. */
  public static PendingEvents forEverything() {
    return new PendingEvents(true);
  }

  @Override
  public boolean supports(Class<?> aggregateType) {
    asked.add(aggregateType);
    return supportsEverything || aggregateType == Parcel.class;
  }

  @Override
  public List<Object> takeEvents(Object aggregate) {
    if (failure != null) {
      RuntimeException thrown = failure;
      failure = null;
      throw thrown;
    }
    if (supportsEverything) {
      return List.of();
    }
    List<Object> events = new ArrayList<>(((Parcel) aggregate).takePending());
    if (extra != null) {
      events.add(extra);
      extra = null;
    }
    return events;
  }

  /** The next {@link #takeEvents} throws {@code failure}. */
  public void failNext(RuntimeException failure) {
    this.failure = failure;
  }

  /** The next {@link #takeEvents} also returns {@code extra}. */
  public void addNext(Object extra) {
    this.extra = extra;
  }

  /** The aggregate types {@link #supports} was asked about, in order. */
  public List<Class<?>> asked() {
    return List.copyOf(asked);
  }
}
