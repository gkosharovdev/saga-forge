package io.github.gkosharovdev.sagaforge.core.spi.log;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/** Shared argument checks of the log records. */
final class Checks {

    private Checks() {
    }

    static void nonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    static void nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static void bothOrNeither(Object first, Object second, String firstName, String secondName) {
        if ((first == null) != (second == null)) {
            throw new IllegalArgumentException(firstName + " and " + secondName
                    + " must be both present or both absent, but " + firstName + " is " + first + " and "
                    + secondName + " is " + second);
        }
    }

    static void presentIff(boolean condition, Object value, String name, String conditionText) {
        if (condition != (value != null)) {
            throw new IllegalArgumentException(name + " must be present if and only if " + conditionText);
        }
    }

    static void commandIdMatchesKind(MessageKind kind, CommandId commandId) {
        presentIff(kind == MessageKind.COMMAND, commandId, "commandId", "kind is COMMAND");
    }
}
