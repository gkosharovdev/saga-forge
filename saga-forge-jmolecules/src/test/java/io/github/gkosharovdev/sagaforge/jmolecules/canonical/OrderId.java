package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import org.jmolecules.ddd.types.Identifier;

/** The order's identity; its text form is the plain value, stable across events and commands. */
public record OrderId(String value) implements Identifier {
  @Override
  public String toString() {
    return value;
  }
}
