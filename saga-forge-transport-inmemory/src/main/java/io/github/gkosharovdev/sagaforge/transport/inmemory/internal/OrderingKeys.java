package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import java.util.List;
import java.util.Optional;

/**
 * Ordering keys ({@code specs/007-in-memory-transport} research §5): messages with the same key are
 * delivered one at a time, in position order. An event's key is the aggregate its link names and
 * its identity, or, for a link naming none, the link's identifier type and the identity
 * (specs/016-marker-spi-jmolecules data-model "Ordering key"); an outcome event's ({@code
 * specs/011-saga-outcome-events}) is its saga, {@code saga:<sagaId>}, the key of the saga's outbox
 * commands; a command's is its identifier value with the command type, else its sending saga, else
 * none.
 *
 * <p>Immutable; thread-safe.
 */
final class OrderingKeys {

  private final DomainTypes domainTypes;

  OrderingKeys(DomainTypes domainTypes) {
    this.domainTypes = domainTypes;
  }

  String ofEvent(Object event) {
    if (event instanceof SagaOutcome outcome) {
      return "saga:" + outcome.sagaId();
    }
    if (!domainTypes.isDomainEvent(event.getClass())) {
      return null;
    }
    List<IdentifierMember> links = domainTypes.identifiers(event.getClass());
    Optional<String> id = identifier(event);
    if (links.isEmpty() || id.isEmpty()) {
      return null;
    }
    IdentifierMember link = links.get(0);
    Class<?> keyType = link.namedAggregate().orElse(link.valueType());
    return keyType.getName() + "#" + id.get();
  }

  String ofCommand(Object command, CommandId commandId) {
    Optional<String> id = identifier(command);
    if (id.isPresent()) {
      return command.getClass().getName() + "#" + id.get();
    }
    return commandId.sagaScoped() ? "saga:" + commandId.sagaId() : null;
  }

  /** The message's identity as text; empty when it declares none or the value is null. */
  private Optional<String> identifier(Object message) {
    try {
      return Optional.of(String.valueOf(domainTypes.identifierValue(message)));
    } catch (IllegalStateException none) {
      return Optional.empty();
    }
  }
}
