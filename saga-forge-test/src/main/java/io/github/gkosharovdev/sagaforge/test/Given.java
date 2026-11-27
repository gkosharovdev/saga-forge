package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;

/**
 * The history of a fixture scenario: events already published and aggregates already stored. Given
 * events run as published events do but do not count towards expectations (FR-004).
 */
public final class Given {

  private final SagaFixture fixture;
  private EventResult last;

  Given(SagaFixture fixture) {
    this.fixture = fixture;
  }

  /**
   * Publishes more history, in order.
   *
   * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage
   *     cannot persist
   */
  public Given given(Object... events) {
    if (events == null) {
      throw new IllegalArgumentException("events must not be null");
    }
    for (Object event : events) {
      last = fixture.publish(event);
    }
    return this;
  }

  /**
   * Publishes more history, in order, each event delivered with {@code metadata} ({@code
   * specs/019-process-metadata}).
   *
   * @throws IllegalArgumentException if {@code metadata} or {@code events} is {@code null}
   * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage
   *     cannot persist
   */
  public Given given(SagaMetadata metadata, Object... events) {
    if (metadata == null) {
      throw new IllegalArgumentException("metadata must not be null");
    }
    if (events == null) {
      throw new IllegalArgumentException("events must not be null");
    }
    for (Object event : events) {
      last = fixture.publish(event, metadata);
    }
    return this;
  }

  /**
   * Seeds aggregates into the in-memory store (handler mode only).
   *
   * @throws IllegalStateException outside handler mode
   */
  public Given givenAggregates(Object... aggregates) {
    fixture.seed(aggregates);
    return this;
  }

  /**
   * Publishes the event under test.
   *
   * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage
   *     cannot persist
   */
  public Then when(Object event) {
    return new Then(fixture, fixture.publish(event), null);
  }

  /**
   * Publishes the event under test, delivered with {@code metadata}, as {@link
   * SagaFixture#when(Object, SagaMetadata)}.
   *
   * @throws IllegalArgumentException if {@code event} or {@code metadata} is {@code null}
   */
  public Then when(Object event, SagaMetadata metadata) {
    return new Then(fixture, fixture.publish(event, metadata), null);
  }

  /**
   * Moves the clock forward and delivers what came due, as {@link SagaFixture#whenTimeElapses}.
   *
   * @throws IllegalArgumentException if {@code duration} is {@code null} or negative
   */
  public Then whenTimeElapses(java.time.Duration duration) {
    return new Then(fixture, fixture.elapse(duration), null);
  }

  /**
   * Sets the clock and delivers what came due, as {@link SagaFixture#whenTimeReaches}.
   *
   * @throws IllegalArgumentException if {@code instant} is {@code null} or before the clock's
   *     current time
   */
  public Then whenTimeReaches(java.time.Instant instant) {
    return new Then(fixture, fixture.reach(instant), null);
  }

  /**
   * Lets time pass in the history: delivers what came due, without counting towards expectations.
   *
   * @throws IllegalArgumentException if {@code duration} is {@code null} or negative
   */
  public Given givenTimeElapses(java.time.Duration duration) {
    last = fixture.elapse(duration);
    return this;
  }

  /**
   * Expectations over the last given event, for a test with no "when" step.
   *
   * @throws IllegalStateException if no event was given
   */
  public Then then() {
    if (last == null) {
      throw new IllegalStateException("no event was given; call given(...) or when(...) first");
    }
    return new Then(fixture, last, null);
  }
}
