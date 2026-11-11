package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.runtime.StorageDetection.Module;

/** Storage detection with a second, test-only storage module on the class path (FR-007, tasks.md T031). */
class StorageDetectionTest {

    private static final Module SECOND = new Module("second", "saga-forge-storage-second",
            StorageDetectionTest.class.getName());

    private final StorageDetection both = new StorageDetection(List.of(StorageDetection.KNOWN.get(0), SECOND),
            getClass().getClassLoader());

    @Test
    @DisplayName("several modules without saga-forge.storage.type fail, naming them and the property")
    void severalWithoutSelection() {
        assertThatThrownBy(() -> both.checkSelection((String) null))
                .isInstanceOf(SagaForgeConfigurationException.class)
                .hasMessageContaining("postgres, second").hasMessageContaining("saga-forge.storage.type");
    }

    @Test
    @DisplayName("several modules with a selection pass")
    void severalWithSelection() {
        assertThatCode(() -> both.checkSelection("second")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a selection naming an absent module fails, naming the artifact")
    void selectionAbsent() {
        StorageDetection secondOnly = new StorageDetection(List.of(StorageDetection.KNOWN.get(0), SECOND),
                new ClassLoader(null) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (name.equals(SECOND.marker())) {
                            return StorageDetectionTest.class;
                        }
                        throw new ClassNotFoundException(name);
                    }
                });
        assertThatThrownBy(() -> secondOnly.checkSelection("postgres"))
                .isInstanceOf(SagaForgeConfigurationException.class)
                .hasMessageContaining("saga-forge-storage-postgres is not on the class path (present: second)");
    }
}
