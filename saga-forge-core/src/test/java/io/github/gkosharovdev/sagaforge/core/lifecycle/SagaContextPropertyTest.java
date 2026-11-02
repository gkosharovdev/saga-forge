package io.github.gkosharovdev.sagaforge.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures.InMemorySagaContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the {@link SagaContext} contract, exercised through the test-only
 * {@link InMemorySagaContext} double.
 *
 * <p>Names, field names, and values are generated as non-blank strings, since the contract rejects
 * {@code null}/blank names and fields and {@code null} values.</p>
 */
@Tag("Feature_001-core-lifecycle-context")
class SagaContextPropertyTest {

    // ── Property A: Saga instance isolation ─────────────────────────────────

    /**
     * Property A: For any state name and any two distinct values, two independently-constructed
     * contexts that each {@code putState} under that same name read back only their own value, and a
     * third fresh context reads {@link java.util.Optional#empty()} for it.
     *
     * <p><b>Validates: spec.md US1 acceptance scenario 4, FR-010</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_A_SagaContext_instance_isolation")
    void contextsNeverObserveEachOthersState(
            @ForAll("nonBlankStrings") String name,
            @ForAll("nonBlankStrings") String firstValue,
            @ForAll("nonBlankStrings") String secondValue) {
        Assume.that(!firstValue.equals(secondValue));

        SagaContext first = new InMemorySagaContext();
        SagaContext second = new InMemorySagaContext();
        SagaContext fresh = new InMemorySagaContext();

        first.putState(name, firstValue);
        second.putState(name, secondValue);

        assertThat(first.getState(name, String.class)).contains(firstValue);
        assertThat(second.getState(name, String.class)).contains(secondValue);
        assertThat(fresh.getState(name, String.class)).isEmpty();
    }

    // ── Property B: Field-level updates preserve previously-set fields ──────

    /**
     * Property B: For any state name and any sequence of {@code updateStateAttribute} calls on
     * distinct field names under that name, every previously-set field remains intact after each
     * call, and the final structured value contains exactly all the fields with their values.
     *
     * <p><b>Validates: SC-005, FR-002a, FR-002b (generalizes spec.md US1 acceptance scenario 5)</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_B_SagaContext_field_update_preservation")
    void fieldUpdatesPreserveAllPreviouslySetFields(
            @ForAll("nonBlankStrings") String name,
            @ForAll("distinctFieldUpdates") Map<String, String> updates) {
        SagaContext context = new InMemorySagaContext();
        Map<String, String> expected = new LinkedHashMap<>();

        for (Map.Entry<String, String> update : updates.entrySet()) {
            context.updateStateAttribute(name, update.getKey(), update.getValue());
            expected.put(update.getKey(), update.getValue());

            assertThat(context.getState(name, Map.class))
                    .hasValueSatisfying(structured ->
                            assertThat(structured).containsExactlyInAnyOrderEntriesOf(expected));
        }

        assertThat(context.getState(name, Map.class))
                .hasValueSatisfying(structured ->
                        assertThat(structured).containsExactlyInAnyOrderEntriesOf(updates));
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<Map<String, String>> distinctFieldUpdates() {
        // Map keys are distinct by construction; iteration order defines the update sequence.
        return Arbitraries.maps(nonBlankStrings(), nonBlankStrings())
                .ofMinSize(1)
                .ofMaxSize(20)
                .map(LinkedHashMap::new);
    }
}
