package io.github.gkosharovdev.sagaforge.core.spi.model;

/**
 * The role a domain type plays in a saga model. A type has at most one role across all enabled
 * vocabularies (scanner rule R30). Handling a command is a property of a method or constructor, not
 * a role of a type.
 */
public enum DomainRole {
  /** A consistency boundary that handles commands and records domain events. */
  AGGREGATE("aggregate"),
  /** A fact about one aggregate; saga steps react to them. */
  DOMAIN_EVENT("domain event"),
  /** A request to change one aggregate or service; saga steps send them. */
  COMMAND("command");

  private final String label;

  DomainRole(String label) {
    this.label = label;
  }

  /**
   * The role in plain words, as error texts use it.
   *
   * @return {@code "aggregate"}, {@code "domain event"} or {@code "command"}
   */
  public String label() {
    return label;
  }
}
