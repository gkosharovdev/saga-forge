package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;

/**
 * Maps {@link SagaLifecycleEntry}s to {@code saga_lifecycle_history} rows with their storage-assigned sequence
 * numbers (research.md §4, §4a). The numbering is pure; only {@link #insertAll} touches JDBC.
 */
final class LifecycleEntryRows {

    /** The eleven history columns, in bind order. */
    static final String INSERT_HISTORY = "INSERT INTO saga_lifecycle_history (saga_id, sequence_number, "
            + "kind, step_name, touched_aggregates, outcome_step, failure_step_name, failure_cause_type, "
            + "failure_message, failure_failed_at, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)";

    private static final String TOUCHED_AGGREGATES_FIELD = "saga_lifecycle_history.touched_aggregates";

    private LifecycleEntryRows() {
    }

    /**
     * An entry with the sequence number storage assigned to it.
     *
     * @param sequenceNumber the entry's position in its instance's history
     * @param entry          the entry
     */
    record Numbered(long sequenceNumber, SagaLifecycleEntry entry) {
    }

    /**
     * Numbers the entries consecutively from {@code firstSequenceNumber}, keeping list order.
     *
     * @param entries             the entries, in hand-over order
     * @param firstSequenceNumber the number of the first entry
     * @return the numbered entries, in the same order; empty for an empty list
     */
    static List<Numbered> number(List<SagaLifecycleEntry> entries, long firstSequenceNumber) {
        List<Numbered> numbered = new ArrayList<>(entries.size());
        long sequenceNumber = firstSequenceNumber;
        for (SagaLifecycleEntry entry : entries) {
            numbered.add(new Numbered(sequenceNumber++, entry));
        }
        return List.copyOf(numbered);
    }

    /**
     * Inserts the entries as one batch, numbered from {@code firstSequenceNumber}; does nothing for an empty list.
     *
     * @param connection          the connection of the save's transaction
     * @param entries             the entries, in hand-over order
     * @param firstSequenceNumber the number of the first entry ({@code MAX + 1} of the instance's history)
     * @throws SQLException if the database fails
     */
    static void insertAll(Connection connection, List<SagaLifecycleEntry> entries, long firstSequenceNumber)
            throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_HISTORY)) {
            for (Numbered numbered : number(entries, firstSequenceNumber)) {
                bind(insert, numbered);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void bind(PreparedStatement insert, Numbered numbered) throws SQLException {
        SagaLifecycleEntry entry = numbered.entry();
        insert.setString(1, entry.sagaId());
        insert.setLong(2, numbered.sequenceNumber());
        insert.setString(3, entry.kind().name());
        insert.setString(4, entry.stepName());
        insert.setString(5, SagaInstanceRows.encodeTouchedAggregates(TOUCHED_AGGREGATES_FIELD,
                entry.touchedAggregates()));
        insert.setString(6, entry.outcomeStep());
        SagaInstanceRows.bindFailure(insert, 7, entry.failure());
        SagaInstanceRows.setInstant(insert, 11, entry.occurredAt());
    }
}
