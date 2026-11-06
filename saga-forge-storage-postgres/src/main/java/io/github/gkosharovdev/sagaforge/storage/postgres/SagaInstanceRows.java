package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Maps a {@link SagaInstance} to and from its {@code saga_instance} row (data-model.md "A. Existing core types
 * persisted").
 *
 * <p>{@code state} and {@code steps} are {@code JSONB} through {@link JsonCodec}. {@code steps} is first mapped to
 * plain maps and lists in the shape
 * {@code {"<step>": {"status": "...", "touchedAggregates": [{"aggregateType": "...", "aggregateId": "..."}]}}},
 * because {@link JsonCodec} accepts no records. {@link SagaFailure} is flattened into the four {@code failure_*}
 * columns. Timestamps are {@code timestamptz}, written as UTC {@link OffsetDateTime}s.</p>
 *
 * <p>Rows are only ever written from an already-valid {@link SagaInstance}, so reading does not re-validate the
 * record's invariants beyond what its own constructor checks (FR-011).</p>
 */
final class SagaInstanceRows {

    /** The {@code saga_instance} columns, in the order {@link #bindInstance} binds them. */
    static final List<String> COLUMNS = List.of("saga_id", "saga_type", "state", "status", "created_at",
            "last_modified_at", "steps", "outcome", "failure_step_name", "failure_cause_type", "failure_message",
            "failure_failed_at");

    static final String STATE_FIELD = "saga_instance.state";
    static final String STEPS_FIELD = "saga_instance.steps";

    private static final String STATUS = "status";
    private static final String TOUCHED_AGGREGATES = "touchedAggregates";
    private static final String AGGREGATE_TYPE = "aggregateType";
    private static final String AGGREGATE_ID = "aggregateId";

    private SagaInstanceRows() {
    }

    /**
     * @param alias the table alias to prefix each column with, e.g. {@code "i"}
     * @return the comma-separated {@link #COLUMNS}, each prefixed with {@code alias.}
     */
    static String selectColumns(String alias) {
        return String.join(", ", COLUMNS.stream().map(column -> alias + "." + column).toList());
    }

    /**
     * Binds {@code instance} to parameters 1 to 12, in {@link #COLUMNS} order; {@code state} (3) and {@code steps}
     * (7) are JSON text for {@code ?::jsonb} placeholders. Everything is encoded before the first parameter is
     * set, so an unsupported value fails with {@link IllegalArgumentException} before any SQL runs.
     *
     * @throws IllegalArgumentException if {@code sagaId}, {@code sagaType}, {@code status}, {@code createdAt}, or
     *                                  {@code lastModifiedAt} is {@code null}, or a {@code state} value is outside
     *                                  the {@link JsonCodec} value set
     */
    static void bindInstance(PreparedStatement statement, SagaInstance instance) throws SQLException {
        requireStorable(instance);
        String state = encodeState(instance.state());
        String steps = encodeSteps(instance.steps());

        statement.setString(1, instance.sagaId());
        statement.setString(2, instance.sagaType());
        statement.setString(3, state);
        statement.setString(4, instance.status().name());
        setInstant(statement, 5, instance.createdAt());
        setInstant(statement, 6, instance.lastModifiedAt());
        statement.setString(7, steps);
        statement.setString(8, instance.outcome());
        bindFailure(statement, 9, instance.failure());
    }

    /**
     * @param row          positioned on a row holding every {@link #COLUMNS} column
     * @param associations the instance's association rows
     * @return the reconstructed instance
     * @throws IllegalStateException if a stored {@code JSONB} column or enum name cannot be decoded
     */
    static SagaInstance fromRow(ResultSet row, Map<String, String> associations) throws SQLException {
        return new SagaInstance(
                row.getString("saga_id"),
                row.getString("saga_type"),
                decodeState(row.getString("state")),
                associations,
                parseEnum(SagaStatus.class, "saga_instance.status", row.getString("status")),
                getInstant(row, "created_at"),
                getInstant(row, "last_modified_at"),
                decodeSteps(row.getString("steps")),
                row.getString("outcome"),
                failureFromRow(row));
    }

    // --- state ----------------------------------------------------------------------------------------------

    static String encodeState(Map<String, Object> state) {
        return JsonCodec.encodeObject(STATE_FIELD, state);
    }

    static Map<String, Object> decodeState(String json) {
        return JsonCodec.decodeObject(STATE_FIELD, json);
    }

    // --- steps ----------------------------------------------------------------------------------------------

    static String encodeSteps(Map<String, StepRecord> steps) {
        Map<String, Object> plain = new LinkedHashMap<>();
        for (Map.Entry<String, StepRecord> entry : steps.entrySet()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put(STATUS, entry.getValue().status().name());
            step.put(TOUCHED_AGGREGATES, toPlain(entry.getValue().touchedAggregates()));
            plain.put(entry.getKey(), step);
        }
        return JsonCodec.encodeObject(STEPS_FIELD, plain);
    }

    static Map<String, StepRecord> decodeSteps(String json) {
        Map<String, StepRecord> steps = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : JsonCodec.decodeObject(STEPS_FIELD, json).entrySet()) {
            String path = STEPS_FIELD + "." + entry.getKey();
            Map<?, ?> step = requireType(Map.class, path, entry.getValue());
            StepStatus status = parseEnum(StepStatus.class, path + "." + STATUS,
                    requireType(String.class, path + "." + STATUS, step.get(STATUS)));
            List<AggregateReference> touched =
                    aggregatesFromPlain(path + "." + TOUCHED_AGGREGATES, step.get(TOUCHED_AGGREGATES));
            try {
                steps.put(entry.getKey(), new StepRecord(status, touched));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(path + " holds an invalid step record: " + e.getMessage(), e);
            }
        }
        return steps;
    }

    // --- touched aggregates (reused by lifecycle history, T019) ------------------------------------------------

    /**
     * @return {@code references} as a list of {@code {"aggregateType": ..., "aggregateId": ...}} maps, in order
     */
    static List<Object> toPlain(List<AggregateReference> references) {
        List<Object> plain = new ArrayList<>(references.size());
        for (AggregateReference reference : references) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put(AGGREGATE_TYPE, reference.aggregateType());
            element.put(AGGREGATE_ID, reference.aggregateId());
            plain.add(element);
        }
        return plain;
    }

    /**
     * @param field the field being decoded, used in error messages
     * @param plain a decoded JSON array of {@code {"aggregateType": ..., "aggregateId": ...}} objects
     * @return the references, in order
     * @throws IllegalStateException if {@code plain} does not have that shape
     */
    static List<AggregateReference> aggregatesFromPlain(String field, Object plain) {
        List<?> elements = requireType(List.class, field, plain);
        List<AggregateReference> references = new ArrayList<>(elements.size());
        int index = 0;
        for (Object element : elements) {
            String path = field + "[" + index + "]";
            Map<?, ?> map = requireType(Map.class, path, element);
            references.add(new AggregateReference(
                    requireType(String.class, path + "." + AGGREGATE_TYPE, map.get(AGGREGATE_TYPE)),
                    requireType(String.class, path + "." + AGGREGATE_ID, map.get(AGGREGATE_ID))));
            index++;
        }
        return references;
    }

    /** @return {@code references} as JSON array text, for a {@code touched_aggregates} column */
    static String encodeTouchedAggregates(String field, List<AggregateReference> references) {
        return JsonCodec.encode(field, toPlain(references));
    }

    /** @return the references stored as JSON array text in a {@code touched_aggregates} column */
    static List<AggregateReference> decodeTouchedAggregates(String field, String json) {
        return aggregatesFromPlain(field, JsonCodec.decode(field, json));
    }

    // --- failure --------------------------------------------------------------------------------------------

    /**
     * Binds {@code failure} to the four consecutive parameters {@code failure_step_name},
     * {@code failure_cause_type}, {@code failure_message}, {@code failure_failed_at} starting at
     * {@code firstIndex}; all four are SQL {@code NULL} when {@code failure} is {@code null}.
     */
    static void bindFailure(PreparedStatement statement, int firstIndex, SagaFailure failure) throws SQLException {
        statement.setString(firstIndex, failure == null ? null : failure.stepName());
        statement.setString(firstIndex + 1, failure == null ? null : failure.causeType());
        statement.setString(firstIndex + 2, failure == null ? null : failure.message());
        setInstant(statement, firstIndex + 3, failure == null ? null : failure.failedAt());
    }

    /**
     * @return the failure in the {@code failure_*} columns of {@code row}, or {@code null} when
     *         {@code failure_step_name} is SQL {@code NULL}
     */
    static SagaFailure failureFromRow(ResultSet row) throws SQLException {
        String stepName = row.getString("failure_step_name");
        if (stepName == null) {
            return null;
        }
        return new SagaFailure(stepName, row.getString("failure_cause_type"), row.getString("failure_message"),
                getInstant(row, "failure_failed_at"));
    }

    // --- timestamps -----------------------------------------------------------------------------------------

    /** Binds {@code instant} as a UTC {@code timestamptz}, or SQL {@code NULL}. */
    static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        }
    }

    /** @return the {@code timestamptz} column as an {@link Instant}, or {@code null} for SQL {@code NULL} */
    static Instant getInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // --- helpers --------------------------------------------------------------------------------------------

    /**
     * @throws IllegalArgumentException if {@code sagaId}, {@code sagaType}, {@code status}, {@code createdAt}, or
     *                                  {@code lastModifiedAt} is {@code null} (G-003-12)
     */
    static void requireStorable(SagaInstance instance) {
        requireField("sagaId", instance.sagaId());
        requireField("sagaType", instance.sagaType());
        requireField("status", instance.status());
        requireField("createdAt", instance.createdAt());
        requireField("lastModifiedAt", instance.lastModifiedAt());
    }

    private static void requireField(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("SagaInstance." + name + " must not be null to be stored");
        }
    }

    private static <T> T requireType(Class<T> type, String path, Object value) {
        if (!type.isInstance(value)) {
            String actual = value == null ? "nothing" : value.getClass().getName();
            throw new IllegalStateException(path + " must hold a " + type.getSimpleName() + ", but holds " + actual);
        }
        return type.cast(value);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String path, String name) {
        if (name == null) {
            throw new IllegalStateException(path + " holds no " + type.getSimpleName());
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(path + " holds unknown " + type.getSimpleName() + " " + name, e);
        }
    }
}
