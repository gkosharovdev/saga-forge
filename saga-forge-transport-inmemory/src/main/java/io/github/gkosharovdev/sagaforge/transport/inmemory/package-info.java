/**
 * The in-memory transport ({@code specs/007-in-memory-transport}): delivery between the modules of one process, with
 * every message first written to a durable message store in the transaction that produced it.
 *
 * <ul>
 *   <li><strong>Guarantee</strong>: at least once. A message from a unit of work that rolled back is never
 *       delivered; a committed one is delivered after commit, and again after a restart if its consumption was not
 *       recorded.</li>
 *   <li><strong>Order</strong>: messages with the same ordering key (an event's aggregate) are delivered one at a time
 *       in recording order; others in parallel on {@code workers} threads.</li>
 *   <li><strong>Failures</strong>: retried with growing backoff, set aside after {@code maxAttempts}; set-aside
 *       messages can be redelivered through {@link io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries}.</li>
 *   <li><strong>Scope</strong>: one running instance per message store; no framework dependency (Spring wiring lives in
 *       the starter).</li>
 * </ul>
 *
 * <p>Assembly order: build the transport, build the engine with {@code transport.dispatcher()} and
 * {@code transport.outbox()}, start the engine, then {@code transport.start(runtime, runtime.commandReceiver())}.</p>
 */
package io.github.gkosharovdev.sagaforge.transport.inmemory;
