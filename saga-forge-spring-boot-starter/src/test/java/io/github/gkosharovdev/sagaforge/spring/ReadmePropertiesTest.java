package io.github.gkosharovdev.sagaforge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The starter README and the generated configuration metadata name the same {@code saga-forge.*} properties
 * ({@code specs/008-spring-boot-starter} T053), so neither drifts silently.
 */
class ReadmePropertiesTest {

    private static final Pattern PROPERTY = Pattern.compile("saga-forge(\\.[a-z0-9-]+)+(\\.\\*)?");

    private static Set<String> readmeProperties() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PROPERTY.matcher(Files.readString(Path.of("README.md")));
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                names.add(matcher.group());
            }
        }
        return names;
    }

    private static Set<String> metadataProperties() throws IOException {
        try (InputStream in = ReadmePropertiesTest.class.getResourceAsStream(
                "/META-INF/spring-configuration-metadata.json")) {
            JsonNode metadata = new ObjectMapper().readTree(in);
            Set<String> names = new TreeSet<>();
            metadata.get("properties").forEach(property -> names.add(property.get("name").asString()));
            names.removeIf(name -> !name.startsWith("saga-forge."));   // Actuator adds management.endpoint.sagaforge.*
            return names;
        }
    }

    @Test
    @DisplayName("every property the README names exists, and every property is in the README")
    void sameProperties() throws IOException {
        Set<String> metadata = metadataProperties();
        assertThat(metadata).isNotEmpty();
        assertThat(readmeProperties()).as("named in the README but unknown").isSubsetOf(metadata);
        assertThat(metadata).as("properties missing from the README").isSubsetOf(readmeProperties());
    }
}
