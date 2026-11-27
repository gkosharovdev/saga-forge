package io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a domain event. Test-only vocabulary (specs/016-marker-spi-jmolecules research R12). */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestEvent {

  /**
   * The aggregate the event concerns; {@code void.class} when it follows from the link's type.
   *
   * @return the aggregate type
   */
  Class<?> of() default void.class;
}
