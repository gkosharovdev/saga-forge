package io.github.gkosharovdev.sagaforge.spring.modulith;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.gkosharovdev.sagaforge.spring.modulith.app.ModulithApp;

/** Spring Modulith's module verification passes with Saga Forge on the class path ({@code specs/008} FR-019). */
class ModulithModulesTest {

    @Test
    @DisplayName("orders, payments and notifications verify as application modules")
    void verifies() {
        ApplicationModules modules = ApplicationModules.of(ModulithApp.class, location -> true); // the app lives in test sources
        modules.verify();
        org.assertj.core.api.Assertions.assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("orders", "payments", "notifications");
    }
}
