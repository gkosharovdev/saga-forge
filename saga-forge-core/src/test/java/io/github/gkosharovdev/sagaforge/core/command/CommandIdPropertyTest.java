package io.github.gkosharovdev.sagaforge.core.command;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

/** {@code CommandId} stability (specs/005-step-retry FR-001, FR-002; research §1). */
class CommandIdPropertyTest {

    @Property
    void equalComponentsGiveEqualValues(@ForAll @AlphaChars @StringLength(min = 1, max = 20) String sagaId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String step,
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String type,
            @ForAll @IntRange(min = 1, max = 50) int ordinal) {
        CommandId first = CommandId.of(sagaId, step, type, ordinal);
        CommandId second = CommandId.of(sagaId, step, type, ordinal);

        assertThat(first).isEqualTo(second);
        assertThat(first.value()).hasSize(36);
        assertThat(UUID.fromString(first.value()).toString()).isEqualTo(first.value());
        assertThat(first.sagaScoped()).isTrue();
        assertThat(first).hasToString(first.value());
    }

    @Property
    void anyDifferentComponentGivesADifferentValue(
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String sagaId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String otherSagaId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String step,
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String otherStep,
            @ForAll @IntRange(min = 1, max = 20) int ordinal,
            @ForAll @IntRange(min = 1, max = 20) int otherOrdinal) {
        String base = CommandId.of(sagaId, step, "T", ordinal).value();
        if (!sagaId.equals(otherSagaId)) {
            assertThat(CommandId.of(otherSagaId, step, "T", ordinal).value()).isNotEqualTo(base);
        }
        if (!step.equals(otherStep)) {
            assertThat(CommandId.of(sagaId, otherStep, "T", ordinal).value()).isNotEqualTo(base);
        }
        if (ordinal != otherOrdinal) {
            assertThat(CommandId.of(sagaId, step, "T", otherOrdinal).value()).isNotEqualTo(base);
        }
        assertThat(CommandId.of(sagaId, step, "U", ordinal).value()).isNotEqualTo(base);
    }

    @Property
    void componentsCannotBeShiftedAcrossTheSeparator(@ForAll @AlphaChars @StringLength(min = 2, max = 10) String a,
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String b) {
        Assume.that(a.length() > 1);
        String head = a.substring(0, 1);
        String tail = a.substring(1);
        assertThat(CommandId.of(a, b, "T", 1).value()).isNotEqualTo(CommandId.of(head, tail + b, "T", 1).value());
    }

    @Test
    void unscopedIdentitiesAreRandomAndCarryNoSagaComponents() {
        CommandId first = CommandId.unscoped("com.example.Charge");
        CommandId second = CommandId.unscoped("com.example.Charge");

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(first.sagaScoped()).isFalse();
        assertThat(first.sagaId()).isNull();
        assertThat(first.ordinal()).isZero();
    }

    @Test
    void rejectsBlankComponentsAndOrdinalsBelowOne() {
        assertThatThrownBy(() -> CommandId.of(" ", "s", "T", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandId.of("i", null, "T", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandId.of("i", "s", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandId.of("i", "s", "T", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandId("i", null, "T", 0, "v", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
