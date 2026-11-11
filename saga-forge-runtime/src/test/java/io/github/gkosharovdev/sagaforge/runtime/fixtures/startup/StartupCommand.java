package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Handled by {@link ServiceWithExtraParameter}. */
@Command
public record StartupCommand(String id) {
}
