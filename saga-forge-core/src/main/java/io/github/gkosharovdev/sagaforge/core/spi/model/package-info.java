/**
 * The domain model's vocabulary SPI (specs/016-marker-spi-jmolecules): which types are aggregates,
 * domain events and commands, which members identify an aggregate or link a message to one, and
 * which executables handle commands.
 *
 * <p>Saga Forge's own annotations are one vocabulary; a {@link
 * io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver} adds another (jMolecules, or
 * an application's own markers). {@link
 * io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes} composes the enabled vocabularies
 * and is what the scanner, the runtime, the transport and the starter ask; none of them reads a
 * domain annotation directly.
 */
package io.github.gkosharovdev.sagaforge.core.spi.model;
