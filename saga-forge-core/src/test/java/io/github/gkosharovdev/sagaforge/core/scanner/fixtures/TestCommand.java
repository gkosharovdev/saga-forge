package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A simple command fixture for scanner tests.
 */
@Command
public record TestCommand(String orderId) {
}
