package io.github.gkosharovdev.sagaforge.spring.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.util.ClassUtils;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.StorageType;

/**
 * Explains why no storage was assembled ({@code specs/008-spring-boot-starter} FR-007, data-model.md "Detection
 * result"): no storage module, several without {@code saga-forge.storage.type}, a type naming an absent module, or a
 * present module without the beans it needs.
 */
final class StorageDetection {

    /** A storage module: its {@code saga-forge.storage.type} value, its artifact and the class that marks it. */
    record Module(String type, String artifact, String marker) {
    }

    /** The storage modules the starter knows. */
    static final List<Module> KNOWN = List.of(new Module("postgres", "saga-forge-storage-postgres",
            "io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider"));

    private final Map<String, Module> present = new LinkedHashMap<>();

    StorageDetection(ClassLoader classLoader) {
        this(KNOWN, classLoader);
    }

    /** For tests: {@code modules} whose markers are looked up in {@code classLoader}. */
    StorageDetection(List<Module> modules, ClassLoader classLoader) {
        modules.stream().filter(module -> ClassUtils.isPresent(module.marker(), classLoader))
                .forEach(module -> present.put(module.type(), module));
    }

    List<String> presentModules() {
        return new ArrayList<>(present.keySet());
    }

    /**
     * Checks the selection before the storage beans are looked up.
     *
     * @throws SagaForgeConfigurationException when several modules are present and none is selected, or the selected one
     *                                         is absent
     */
    void checkSelection(StorageType selected) {
        checkSelection(selected == null ? null : selected.name().toLowerCase(Locale.ROOT));
    }

    void checkSelection(String selected) {
        if (selected != null && !present.containsKey(selected)) {
            String artifact = KNOWN.stream().filter(module -> module.type().equals(selected)).map(Module::artifact)
                    .findFirst().orElse("the " + selected + " storage module");
            throw new SagaForgeConfigurationException("saga-forge.storage.type is " + selected + ", but " + artifact
                    + " is not on the class path (present: " + names() + ").",
                    "Add " + artifact + " to the application's dependencies, or change saga-forge.storage.type.");
        }
        if (selected == null && present.size() > 1) {
            throw new SagaForgeConfigurationException("Several Saga Forge storage modules are on the class path: "
                    + names() + ".", "Select one with saga-forge.storage.type, or remove the others.");
        }
    }

    /** @return the error for a missing {@code StorageProvider}/{@code SagaContextStore} after selection passed */
    SagaForgeConfigurationException missingStorage() {
        if (present.isEmpty()) {
            return new SagaForgeConfigurationException(
                    "No Saga Forge storage module is on the class path; a saga needs durable storage.",
                    "Add saga-forge-storage-postgres (the supported storage module), or define your own StorageProvider"
                            + " and SagaContextStore beans.");
        }
        return new SagaForgeConfigurationException("The " + names() + " storage module is on the class path, but no"
                + " storage was assembled: it needs a DataSource and a PlatformTransactionManager bean.",
                "Configure a DataSource (spring.datasource.*) with spring-boot-starter-jdbc, or define your own"
                        + " StorageProvider and SagaContextStore beans.");
    }

    private String names() {
        return present.isEmpty() ? "none" : String.join(", ", present.keySet());
    }
}
