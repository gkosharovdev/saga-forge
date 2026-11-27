package io.github.gkosharovdev.sagaforge.jmolecules;

import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.core.spi.model.Marker;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jmolecules.ddd.types.Association;
import org.jmolecules.ddd.types.Identifier;

/**
 * The members linking a jMolecules event or command to the aggregate it concerns
 * (contracts/jmolecules-module.md, "Link members"): declared fields typed {@code Association<A,
 * ID>} (naming {@code A}, read through {@code getId()}), annotated {@code @Association} (naming its
 * {@code aggregateType} if set), or typed with an {@link Identifier}. A record component counts
 * once, through its field. Members of other types are never links (memory.md G-016-05).
 *
 * <p>Stateless; thread-safe.
 */
final class LinkMembers {

  static final Marker ASSOCIATION_TYPE = JMoleculesDomainTypeResolver.marker("Association");
  static final Marker ASSOCIATION = JMoleculesDomainTypeResolver.marker("@Association");
  static final Marker IDENTIFIER = JMoleculesDomainTypeResolver.marker("Identifier");

  private LinkMembers() {}

  static List<IdentifierMember> of(Class<?> type) {
    List<IdentifierMember> links = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
        link(field).ifPresent(links::add);
      }
    }
    return List.copyOf(links);
  }

  private static Optional<IdentifierMember> link(Field field) {
    if (Association.class.isAssignableFrom(field.getType())) {
      return Optional.of(associationLink(field));
    }
    org.jmolecules.ddd.annotation.Association annotated =
        field.getAnnotation(org.jmolecules.ddd.annotation.Association.class);
    if (annotated != null) {
      IdentifierMember member = IdentifierMember.ofField(field, ASSOCIATION);
      return Optional.of(
          annotated.aggregateType() == void.class
              ? member
              : member.naming(annotated.aggregateType()));
    }
    if (Identifier.class.isAssignableFrom(field.getType())) {
      return Optional.of(IdentifierMember.ofField(field, IDENTIFIER));
    }
    return Optional.empty();
  }

  /**
   * A typed {@code Association<A, ID>} names {@code A} and holds an {@code ID}; with raw or
   * wildcard type arguments the aggregate cannot be read, and the link names none (R31 reports it).
   */
  private static IdentifierMember associationLink(Field field) {
    IdentifierMember member =
        IdentifierMember.ofField(field, ASSOCIATION_TYPE)
            .unwrappedBy(
                value -> ((Association<?, ?>) value).getId(),
                typeArgument(field, 1).orElse(Identifier.class));
    return typeArgument(field, 0).map(member::naming).orElse(member);
  }

  private static Optional<Class<?>> typeArgument(Field field, int index) {
    Type generic = field.getGenericType();
    if (generic instanceof ParameterizedType parameterized
        && parameterized.getActualTypeArguments()[index] instanceof Class<?> argument) {
      return Optional.of(argument);
    }
    return Optional.empty();
  }
}
