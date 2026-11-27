package io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.core.spi.model.Marker;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A third vocabulary, used only by tests, proving the SPI is not shaped around jMolecules
 * (specs/016-marker-spi-jmolecules US4, research R12): {@link TestAggregate}, {@link TestId} on a
 * field or accessor, {@link TestEvent}, {@link TestCommand} and {@link TestHandles}.
 */
public final class TestDomainTypeResolver implements DomainTypeResolver {

  /** The vocabulary's name. */
  public static final String NAME = "Test";

  private static final Marker AGGREGATE = marker("@TestAggregate");
  private static final Marker EVENT = marker("@TestEvent");
  private static final Marker COMMAND = marker("@TestCommand");
  private static final Marker ID = marker("@TestId");
  private static final Marker HANDLES = marker("@TestHandles");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<RoleMarker> role(Class<?> type) {
    if (type.isAnnotationPresent(TestAggregate.class)) {
      return Optional.of(new RoleMarker(DomainRole.AGGREGATE, AGGREGATE));
    }
    if (type.isAnnotationPresent(TestEvent.class)) {
      return Optional.of(new RoleMarker(DomainRole.DOMAIN_EVENT, EVENT));
    }
    if (type.isAnnotationPresent(TestCommand.class)) {
      return Optional.of(new RoleMarker(DomainRole.COMMAND, COMMAND));
    }
    return Optional.empty();
  }

  @Override
  public List<IdentifierMember> identifiers(Class<?> type) {
    List<IdentifierMember> members = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (field.isAnnotationPresent(TestId.class) && !Modifier.isStatic(field.getModifiers())) {
        members.add(named(type, IdentifierMember.ofField(field, ID)));
      }
    }
    Set<String> components = componentNames(type);
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(TestId.class) && !components.contains(method.getName())) {
        members.add(named(type, IdentifierMember.ofAccessor(method, ID)));
      }
    }
    return List.copyOf(members);
  }

  @Override
  public Optional<Marker> commandHandler(Executable member) {
    return member.isAnnotationPresent(TestHandles.class) ? Optional.of(HANDLES) : Optional.empty();
  }

  @Override
  public String describe(DomainRole role) {
    return switch (role) {
      case AGGREGATE -> AGGREGATE.display();
      case DOMAIN_EVENT -> EVENT.display();
      case COMMAND -> COMMAND.display();
    };
  }

  @Override
  public String describeIdentifier(DomainRole role) {
    return ID.display() + " member";
  }

  private static IdentifierMember named(Class<?> type, IdentifierMember member) {
    TestEvent event = type.getAnnotation(TestEvent.class);
    return event == null || event.of() == void.class ? member : member.naming(event.of());
  }

  /** A record component annotated {@code @TestId} counts once, through its field. */
  private static Set<String> componentNames(Class<?> type) {
    RecordComponent[] components = type.getRecordComponents();
    return components == null
        ? Set.of()
        : Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toSet());
  }

  private static Marker marker(String display) {
    return new Marker(NAME, display);
  }
}
