/**
 * The storage-agnostic message store behind a transactional outbox ({@code specs/007-in-memory-transport}): the
 * {@link io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore} contract, implemented by storage modules, and the
 * records it exchanges with a transport's relay.
 *
 * <p>Vocabulary: a transport <em>appends</em> {@link io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage}s in the
 * unit of work's transaction; its relay <em>leases</em> committed entries (reserves them for a limited time), delivers them, and marks them
 * <em>consumed</em>, records a failure, or <em>sets them aside</em>. Payloads are plain objects; encoding belongs to the
 * storage module.</p>
 */
package io.github.gkosharovdev.sagaforge.core.spi.store;
