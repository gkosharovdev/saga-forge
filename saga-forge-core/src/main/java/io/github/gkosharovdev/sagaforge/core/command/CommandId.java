package io.github.gkosharovdev.sagaforge.core.command;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The idempotency identity of one dispatched command ({@code specs/005-step-retry} FR-001 to FR-003).
 *
 * <p>For a command sent from a saga step, the identity is a pure function of the saga instance, the step, the
 * command type and the command's position among commands of that type sent by that step run: it is the same in
 * every attempt, every redelivery and every process. {@link #value()} is a name-based UUID over those four
 * components, short enough to pass to an outside system as its idempotency key. A {@code @CommandHandler} receives
 * it by declaring a {@code CommandId} parameter.</p>
 *
 * <p>A command sent outside a saga step gets an {@linkplain #unscoped(String) unscoped} identity: a random value
 * that carries no guarantee.</p>
 *
 * @param sagaId      the saga instance; non-blank if {@code sagaScoped}, otherwise {@code null}
 * @param stepName    the step that sent the command; non-blank if {@code sagaScoped}, otherwise {@code null}
 * @param commandType fully qualified class name of the command; non-blank
 * @param ordinal     1-based position among commands of {@code commandType} sent by that step run if
 *                    {@code sagaScoped}, otherwise 0
 * @param value       the identity value; non-blank
 * @param sagaScoped  whether the command was sent from a saga step
 * @throws IllegalArgumentException if a component violates the rules above
 */
public record CommandId(String sagaId, String stepName, String commandType, int ordinal, String value,
        boolean sagaScoped) {

    public CommandId {
        requireNonBlank(commandType, "commandType");
        requireNonBlank(value, "value");
        if (sagaScoped) {
            requireNonBlank(sagaId, "sagaId");
            requireNonBlank(stepName, "stepName");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be at least 1 for a saga-scoped command, but is "
                        + ordinal);
            }
        } else if (sagaId != null || stepName != null || ordinal != 0) {
            throw new IllegalArgumentException(
                    "an unscoped command identity has no sagaId, no stepName and ordinal 0");
        }
    }

    /**
     * @param sagaId      the saga instance; non-blank
     * @param stepName    the step that sends the command; non-blank
     * @param commandType fully qualified class name of the command; non-blank
     * @param ordinal     1-based position among commands of that type sent by that step run
     * @return the deterministic identity of that command
     * @throws IllegalArgumentException if an argument is blank or {@code ordinal < 1}
     */
    public static CommandId of(String sagaId, String stepName, String commandType, int ordinal) {
        requireNonBlank(sagaId, "sagaId");
        requireNonBlank(stepName, "stepName");
        requireNonBlank(commandType, "commandType");
        String name = sagaId + '\0' + stepName + '\0' + commandType + '\0' + ordinal;
        String value = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
        return new CommandId(sagaId, stepName, commandType, ordinal, value, true);
    }

    /**
     * @param commandType fully qualified class name of the command; non-blank
     * @return a fresh random identity for a command sent outside a saga step
     * @throws IllegalArgumentException if {@code commandType} is blank
     */
    public static CommandId unscoped(String commandType) {
        return new CommandId(null, null, commandType, 0, UUID.randomUUID().toString(), false);
    }

    /**
     * @return {@link #value()}
     */
    @Override
    public String toString() {
        return value;
    }

    private static void requireNonBlank(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
