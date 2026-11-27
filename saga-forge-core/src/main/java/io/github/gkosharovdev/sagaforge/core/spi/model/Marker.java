package io.github.gkosharovdev.sagaforge.core.spi.model;

/**
 * The marker that gave a type its role or a member its meaning, as error texts name it.
 *
 * @param vocabulary the name of the vocabulary it belongs to, the resolver's {@link
 *     DomainTypeResolver#name()}, for example {@code "Saga Forge"} or {@code "jMolecules"}
 * @param display how error texts name it, for example {@code "@Aggregate"} or {@code "jMolecules
 *     AggregateRoot<T, ID>"}
 */
public record Marker(String vocabulary, String display) {

  /**
   * Checks both components.
   *
   * @throws IllegalArgumentException if a component is null or blank
   */
  public Marker {
    requireText(vocabulary, "vocabulary");
    requireText(display, "display");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
