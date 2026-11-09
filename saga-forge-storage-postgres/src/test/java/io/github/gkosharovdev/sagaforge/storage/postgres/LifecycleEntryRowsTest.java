package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.LifecycleEntryRows.Numbered;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LifecycleEntryRows} numbering and statement text (specs/004-saga-engine research.md §4a, §15). Pure: no
 * database, so it does not touch {@link PostgresTestSupport}.
 */
class LifecycleEntryRowsTest {

    private static final String SAGA_ID = "s-1";
    private static final Instant AT = Instant.parse("2026-01-01T10:00:00Z");

    private static SagaLifecycleEntry created() {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.CREATED, null, List.of(), null, null, AT);
    }

    private static SagaLifecycleEntry stepCompleted(String step) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.STEP_COMPLETED, step, List.of(), null, null,
                AT);
    }

    @Test
    @DisplayName("numbering starts at the given value, is consecutive and keeps list order")
    void numbersConsecutivelyInListOrder() {
        SagaLifecycleEntry zeta = stepCompleted("zeta");
        SagaLifecycleEntry alpha = stepCompleted("alpha");

        List<Numbered> numbered = LifecycleEntryRows.number(List.of(created(), zeta, alpha), 7);

        assertThat(numbered).containsExactly(
                new Numbered(7, created()), new Numbered(8, zeta), new Numbered(9, alpha));
    }

    @Test
    @DisplayName("an empty list yields nothing")
    void emptyListYieldsNothing() {
        assertThat(LifecycleEntryRows.number(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("INSERT_HISTORY names the eleven columns in bind order")
    void insertNamesElevenColumnsInBindOrder() {
        assertThat(LifecycleEntryRows.INSERT_HISTORY)
                .contains("(saga_id, sequence_number, kind, step_name, touched_aggregates, outcome_step, "
                        + "failure_step_name, failure_cause_type, failure_message, failure_failed_at, occurred_at)")
                .contains("VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)");
    }
}
