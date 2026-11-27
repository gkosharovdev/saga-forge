package io.github.gkosharovdev.sagaforge.core.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainTypes#builtIn()} answers exactly what reading Saga Forge's annotations directly gave
 * before vocabularies existed, on every type of core's tests (specs/016-marker-spi-jmolecules
 * FR-011, SC-003).
 */
class BuiltInResolverTest {

  private static final Path TEST_CLASSES = Path.of("target/test-classes");

  private final DomainTypes builtIn = DomainTypes.builtIn();

  @Test
  @DisplayName("roles, identifiers, event aggregates, deliveries and handlers match direct reads")
  void matchesDirectReads() {
    List<Class<?>> types = testTypes();
    assertThat(types).hasSizeGreaterThan(100);

    for (Class<?> type : types) {
      assertThat(builtIn.role(type)).as("role of %s", type).isEqualTo(directRole(type));
      assertThat(members(builtIn.identifiers(type)))
          .as("identifiers of %s", type)
          .isEqualTo(directIdentifiers(type));
      if (type.isAnnotationPresent(DomainEvent.class)) {
        assertThat(builtIn.identifiers(type))
            .as("named aggregate of %s", type)
            .allSatisfy(
                link ->
                    assertThat(link.namedAggregate())
                        .contains(type.getAnnotation(DomainEvent.class).aggregate()));
      }
      assertThat(builtIn.delivery(type)).as("delivery of %s", type).isEqualTo(directDelivery(type));
      for (Executable executable : executables(type)) {
        assertThat(builtIn.commandHandler(executable).isPresent())
            .as("handler %s", executable)
            .isEqualTo(executable.isAnnotationPresent(CommandHandler.class));
      }
    }
  }

  @Test
  @DisplayName("the built-in vocabulary is the only one, and its texts are the annotation names")
  void texts() {
    assertThat(builtIn.vocabularies()).containsExactly("Saga Forge");
    assertThat(builtIn.describe(DomainRole.AGGREGATE)).isEqualTo("@Aggregate");
    assertThat(builtIn.describe(DomainRole.DOMAIN_EVENT)).isEqualTo("@DomainEvent");
    assertThat(builtIn.describe(DomainRole.COMMAND)).isEqualTo("@Command");
    assertThat(builtIn.notMarkedMessage(String.class, DomainRole.DOMAIN_EVENT))
        .isEqualTo("java.lang.String is not annotated @DomainEvent");
    assertThat(DomainTypes.of()).isSameAs(builtIn);
  }

  private static Optional<DomainRole> directRole(Class<?> type) {
    if (type.isAnnotationPresent(Aggregate.class)) {
      return Optional.of(DomainRole.AGGREGATE);
    }
    if (type.isAnnotationPresent(DomainEvent.class)) {
      return Optional.of(DomainRole.DOMAIN_EVENT);
    }
    return type.isAnnotationPresent(Command.class)
        ? Optional.of(DomainRole.COMMAND)
        : Optional.empty();
  }

  private static List<Member> directIdentifiers(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(field -> field.isAnnotationPresent(AggregateIdentifier.class))
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .<Member>map(field -> field)
        .toList();
  }

  private static CommandDelivery directDelivery(Class<?> type) {
    Command command = type.getAnnotation(Command.class);
    return command == null ? CommandDelivery.IN_UNIT_OF_WORK : command.delivery();
  }

  private static List<Member> members(List<IdentifierMember> identifiers) {
    return identifiers.stream().map(IdentifierMember::member).toList();
  }

  private static List<Executable> executables(Class<?> type) {
    List<Executable> executables = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
    executables.addAll(Arrays.asList(type.getDeclaredConstructors()));
    return executables;
  }

  private static List<Class<?>> testTypes() {
    try (Stream<Path> files = Files.walk(TEST_CLASSES)) {
      return files
          .map(TEST_CLASSES::relativize)
          .map(Path::toString)
          .filter(name -> name.endsWith(".class") && !name.contains("module-info"))
          .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
          .map(BuiltInResolverTest::load)
          .flatMap(Optional::stream)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Optional<Class<?>> load(String name) {
    try {
      return Optional.of(Class.forName(name, false, BuiltInResolverTest.class.getClassLoader()));
    } catch (ClassNotFoundException | LinkageError e) {
      return Optional.empty();
    }
  }
}
