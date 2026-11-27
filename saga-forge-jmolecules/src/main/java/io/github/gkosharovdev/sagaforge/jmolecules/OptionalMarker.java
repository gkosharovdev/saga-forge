package io.github.gkosharovdev.sagaforge.jmolecules;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

/**
 * A jMolecules annotation or type from an optional artifact ({@code jmolecules-events}, {@code
 * jmolecules-cqrs-architecture}), looked up once by name without initialising it. When the artifact
 * is absent, nothing carries the marker.
 *
 * <p>Immutable; thread-safe.
 */
final class OptionalMarker {

  private final Class<?> type;

  private OptionalMarker(Class<?> type) {
    this.type = type;
  }

  /** The class named {@code className}, or a marker nothing carries if it is not on the path. */
  static OptionalMarker named(String className, ClassLoader loader) {
    try {
      return new OptionalMarker(Class.forName(className, false, loader));
    } catch (ClassNotFoundException | LinkageError absent) {
      return new OptionalMarker(null);
    }
  }

  boolean isPresent() {
    return type != null;
  }

  /** Whether {@code element} is annotated with this marker, an annotation type. */
  boolean annotates(AnnotatedElement element) {
    return type != null && type.isAnnotation() && element.isAnnotationPresent(annotationType());
  }

  /** Whether {@code candidate} implements or extends this marker, a type. */
  boolean isSupertypeOf(Class<?> candidate) {
    return type != null && !type.isAnnotation() && type.isAssignableFrom(candidate);
  }

  @SuppressWarnings("unchecked")
  private Class<? extends Annotation> annotationType() {
    return (Class<? extends Annotation>) type;
  }

  @Override
  public String toString() {
    return Optional.ofNullable(type).map(Class::getName).orElse("absent");
  }
}
