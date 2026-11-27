package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * In core, only the built-in vocabulary reads Saga Forge's domain annotations reflectively
 * (specs/016-marker-spi-jmolecules FR-003, SC-005); everything else asks {@code DomainTypes}. Core
 * has no ArchUnit (zero-dependency module), so this guard reads the main sources.
 */
class CoreDomainAnnotationAccessTest {

  private static final Path MAIN_SOURCES = Path.of("src/main/java");

  private static final Pattern DIRECT_READ =
      Pattern.compile(
          "\\.(isAnnotationPresent|getAnnotation|getDeclaredAnnotation|getAnnotationsByType)\\("
              + "\\s*(Aggregate|AggregateIdentifier|DomainEvent|Command|CommandHandler)\\.class");

  /** Source files allowed to read the annotations, relative to the main source root. */
  private static final Set<String> ALLOWED =
      Set.of("io/github/gkosharovdev/sagaforge/core/spi/model/SagaForgeAnnotations.java");

  @Test
  void onlyTheBuiltInVocabularyReadsTheDomainAnnotations() {
    assertThat(filesReadingTheAnnotations()).isSubsetOf(ALLOWED);
  }

  private static List<String> filesReadingTheAnnotations() {
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      return files
          .filter(file -> file.toString().endsWith(".java"))
          .filter(CoreDomainAnnotationAccessTest::readsTheAnnotations)
          .map(file -> MAIN_SOURCES.relativize(file).toString().replace('\\', '/'))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean readsTheAnnotations(Path file) {
    try {
      return DIRECT_READ.matcher(Files.readString(file)).find();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
