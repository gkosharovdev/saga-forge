package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Released schema versions are frozen (specs/018 research R4): a listed file's SHA-256 never
 * changes, and only versions newer than every listed one may be missing from the list.
 */
class ReleasedMigrationsTest {

  private static final String LIST =
      "/io/github/gkosharovdev/sagaforge/storage/postgres/released-migrations.sha256";

  @Test
  @DisplayName("every released version keeps its checksum")
  void releasedVersionsAreUnchanged() throws Exception {
    Map<String, String> released = released();
    Map<String, Path> files = files();
    assertThat(released).isNotEmpty();
    for (Map.Entry<String, String> entry : released.entrySet()) {
      Path file = files.get(entry.getKey());
      assertThat(file).as("released version %s still ships", entry.getKey()).isNotNull();
      assertThat(sha256(file))
          .as(
              "%s is a released schema version and is frozen; add a new version instead",
              entry.getKey())
          .isEqualTo(entry.getValue());
    }
  }

  @Test
  @DisplayName("only versions newer than every released one are unlisted")
  void unlistedVersionsAreTheNewest() throws Exception {
    Map<String, String> released = released();
    int newestReleased =
        released.keySet().stream().mapToInt(ReleasedMigrationsTest::number).max().orElse(0);
    for (String name : files().keySet()) {
      if (!released.containsKey(name)) {
        assertThat(number(name)).as("%s is unlisted", name).isGreaterThan(newestReleased);
      }
    }
  }

  private static Map<String, String> released() throws IOException {
    Map<String, String> released = new LinkedHashMap<>();
    try (InputStream in = ReleasedMigrationsTest.class.getResourceAsStream(LIST)) {
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.strip().split("\\s+");
        released.put(parts[1], parts[0]);
      }
    }
    return released;
  }

  private static Map<String, Path> files() throws Exception {
    Map<String, Path> files = new LinkedHashMap<>();
    List<Path> paths = MigrationResourcesTest.migrationFiles();
    paths.forEach(path -> files.put(path.getFileName().toString(), path));
    return files;
  }

  private static int number(String fileName) {
    return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
