package io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an aggregate's identifier, or an event's or command's link. Test-only vocabulary
 * (specs/016-marker-spi-jmolecules research R12).
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestId {}
