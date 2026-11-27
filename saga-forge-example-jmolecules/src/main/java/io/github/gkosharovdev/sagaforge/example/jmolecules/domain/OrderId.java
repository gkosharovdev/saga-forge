package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.util.Objects;
import org.jmolecules.ddd.types.Identifier;

/**
 * An order's identity. Its text form is the plain value, so associations and ordering keys read the
 * same text from every event and command that carries it.
 */
public record OrderId(String value) implements Identifier {

  public OrderId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value;
  }
}
