package io.github.gkosharovdev.sagaforge.test;

/**
 * The history of a fixture scenario: events already published and aggregates already stored. Given events run as
 * published events do but do not count towards expectations (FR-004).
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
     * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage cannot persist
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
     * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage cannot persist
     */
    public Then when(Object event) {
        return new Then(fixture, fixture.publish(event), null);
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
