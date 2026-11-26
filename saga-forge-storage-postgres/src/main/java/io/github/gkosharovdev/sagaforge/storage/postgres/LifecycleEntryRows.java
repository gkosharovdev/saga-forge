package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps {@link SagaLifecycleEntry}s to {@code saga_lifecycle_history} rows with their
 * storage-assigned sequence numbers (research.md §4, §4a). The numbering is pure; only {@link
 * #insertAll} touches JDBC.
 */
final class LifecycleEntryRows {

  /** The fourteen history columns, in bind order. */
  static final String INSERT_HISTORY =
      "INSERT INTO saga_lifecycle_history (saga_id, sequence_number, "
          + "kind, step_name, touched_aggregates, outcome_step, failure_step_name, failure_cause_type, "
          + "failure_message, failure_failed_at, failure_classification, failure_attempts, occurred_at, "
          + "compensation_trigger, operator, operator_reason) "
          + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

  /** An instance's history in stored order ({@code specs/015-saga-operations}). */
  static final String SELECT_HISTORY =
      "SELECT kind, step_name, touched_aggregates, outcome_step, failure_step_name, "
          + "failure_cause_type, failure_message, failure_failed_at, failure_classification, "
          + "failure_attempts, occurred_at, compensation_trigger, operator, operator_reason "
          + "FROM saga_lifecycle_history WHERE saga_id = ? ORDER BY sequence_number";

  private static final String TRIGGER_FIELD = "saga_lifecycle_history.compensation_trigger";

  private static final String TOUCHED_AGGREGATES_FIELD =
      "saga_lifecycle_history.touched_aggregates";

  private LifecycleEntryRows() {}

  /**
   * An entry with the sequence number storage assigned to it.
   *
   * @param sequenceNumber the entry's position in its instance's history
   * @param entry the entry
   */
  record Numbered(long sequenceNumber, SagaLifecycleEntry entry) {}

  /**
   * Numbers the entries consecutively from {@code firstSequenceNumber}, keeping list order.
   *
   * @param entries the entries, in hand-over order
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
   * Inserts the entries as one batch, numbered from {@code firstSequenceNumber}; does nothing for
   * an empty list.
   *
   * @param connection the connection of the save's transaction
   * @param entries the entries, in hand-over order
   * @param firstSequenceNumber the number of the first entry ({@code MAX + 1} of the instance's
   *     history)
   * @throws SQLException if the database fails
   */
  static void insertAll(
      Connection connection, List<SagaLifecycleEntry> entries, long firstSequenceNumber)
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
    insert.setString(
        5,
        SagaInstanceRows.encodeTouchedAggregates(
            TOUCHED_AGGREGATES_FIELD, entry.touchedAggregates()));
    insert.setString(6, entry.outcomeStep());
    SagaInstanceRows.bindFailure(insert, 7, entry.failure());
    SagaInstanceRows.setInstant(insert, 13, entry.occurredAt());
    insert.setString(14, SagaInstanceRows.encodeTrigger(TRIGGER_FIELD, entry.trigger()));
    OperatorRequest operator = entry.operator();
    insert.setString(15, operator == null ? null : operator.operator());
    insert.setString(16, operator == null ? null : operator.reason());
  }

  /**
   * Reads every history row of {@code sagaId}, in stored order.
   *
   * @param connection the connection to read on
   * @param sagaId the instance
   * @return the entries; empty for an unknown instance
   * @throws SQLException if the database fails
   * @throws IllegalStateException if a stored value cannot be decoded
   */
  static List<SagaLifecycleEntry> selectAll(Connection connection, String sagaId)
      throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(SELECT_HISTORY)) {
      select.setString(1, sagaId);
      List<SagaLifecycleEntry> entries = new ArrayList<>();
      try (ResultSet row = select.executeQuery()) {
        while (row.next()) {
          entries.add(fromRow(sagaId, row));
        }
      }
      return List.copyOf(entries);
    }
  }

  private static SagaLifecycleEntry fromRow(String sagaId, ResultSet row) throws SQLException {
    String operator = row.getString("operator");
    String touched = row.getString("touched_aggregates");
    return new SagaLifecycleEntry(
        sagaId,
        SagaLifecycleEventKind.valueOf(row.getString("kind")),
        row.getString("step_name"),
        touched == null
            ? List.of()
            : SagaInstanceRows.decodeTouchedAggregates(TOUCHED_AGGREGATES_FIELD, touched),
        row.getString("outcome_step"),
        SagaInstanceRows.failureFromRow(row),
        SagaInstanceRows.getInstant(row, "occurred_at"),
        SagaInstanceRows.decodeTrigger(TRIGGER_FIELD, row.getString("compensation_trigger")),
        operator == null ? null : new OperatorRequest(operator, row.getString("operator_reason")));
  }
}
