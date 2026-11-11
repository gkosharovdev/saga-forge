package io.github.gkosharovdev.sagaforge.spring.runtime;

import java.util.Collection;

/**
 * Adds classes to the saga model that scanning does not find, for example sagas or handler hosts in a library outside
 * the application's packages ({@code specs/008-spring-boot-starter} FR-004). Define one or more as beans.
 */
@FunctionalInterface
public interface SagaModelContributor {

    /** @return sagas, aggregates and handler, loader or saver hosts to register; never {@code null} */
    Collection<Class<?>> classes();
}
