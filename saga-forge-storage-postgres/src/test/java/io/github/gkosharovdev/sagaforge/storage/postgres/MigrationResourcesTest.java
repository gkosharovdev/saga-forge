package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped migrations (specs/018 contracts/migration-resources.md): Flyway-named, contiguous
 * from V1, one per {@link PostgresSchema#versions()} entry, transactional, without placeholders and
 * without a schema name.
 */
class MigrationResourcesTest {

  private static final Pattern NAME = Pattern.compile("V(\\d+)__([a-z_]+)\\.sql");

  static List<Path> migrationFiles() throws IOException, URISyntaxException {
    URL dir =
        MigrationResourcesTest.class.getResource(
            "/io/github/gkosharovdev/sagaforge/storage/postgres/migration");
    try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
      return files.sorted().toList();
    }
  }

  @Test
  @DisplayName(
      "file names are V<N>__words.sql, contiguous from 1, matching PostgresSchema.versions()")
  void namesMatchTheVersions() throws Exception {
    List<SchemaVersion> fromFiles =
        migrationFiles().stream()
            .map(path -> path.getFileName().toString())
            .map(
                name -> {
                  Matcher matcher = NAME.matcher(name);
                  assertThat(matcher.matches()).as(name).isTrue();
                  return new SchemaVersion(
                      Integer.parseInt(matcher.group(1)), matcher.group(2).replace('_', ' '));
                })
            .sorted()
            .toList();
    assertThat(fromFiles).containsExactlyElementsOf(PostgresSchema.versions());
    for (int i = 0; i < fromFiles.size(); i++) {
      assertThat(fromFiles.get(i).number()).isEqualTo(i + 1);
    }
  }

  @Test
  @DisplayName("no placeholder, no non-transactional statement and no schema name")
  void statementsAreSafeForFlyway() throws Exception {
    for (Path file : migrationFiles()) {
      String sql =
          Files.readString(file, StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.strip().startsWith("--"))
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(sql)
          .as(file.getFileName().toString())
          .doesNotContain("${")
          .doesNotContainIgnoringCase("CONCURRENTLY")
          .doesNotContainIgnoringCase("VACUUM")
          .doesNotContainIgnoringCase("public.")
          .doesNotContain("\"public\"");
    }
  }
}
