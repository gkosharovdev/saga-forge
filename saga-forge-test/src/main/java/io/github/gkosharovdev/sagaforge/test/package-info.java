/**
 * Saga Forge's test fixture ({@code specs/009-saga-test-fixture}): given/when/then tests of sagas on the shipped
 * runtime, in memory, with no Spring context, database or transport.
 *
 * <p>Start with {@link io.github.gkosharovdev.sagaforge.test.SagaFixture}. By default every command a step sends is
 * recorded and the test answers it by publishing the resulting event (record and answer); with
 * {@code handlers(...)} the application's own command handlers and aggregates run as in production. Failures are
 * {@link io.github.gkosharovdev.sagaforge.test.SagaExpectationError}s, which every JVM test framework reports; no
 * JUnit extension is needed.</p>
 *
 * <p>Only the public types of this package are API; the package-private ones are the fixture's in-memory
 * plug-ins.</p>
 */
package io.github.gkosharovdev.sagaforge.test;
