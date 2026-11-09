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

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
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
 * {@code {"<step>": {"status": "...", "touchedAggregates": [{"aggregateType": "...", "aggregateId": "..."}],
 * "attempts": n, "handledCommands": [{"sagaId": "...", "stepName": "...", "commandType": "...", "ordinal": n,
 * "value": "..."}]}}}, because {@link JsonCodec} accepts no records; {@code attempts} and {@code handledCommands}
 * (005) default to 1 (0 if not completed) and {@code []} when absent, so rows written before 005 load unchanged.
 * {@link SagaFailure} is flattened into the six {@code failure_*} columns (classification and attempts since 005,
 * defaulting to {@code UNCLASSIFIED} and 1). {@link PendingRetry} is the {@code pending_retry} {@code JSONB} column
 * (005, contracts/storage-delta.md). Timestamps are {@code timestamptz}, written as UTC {@link OffsetDateTime}s.</p>
 *
 * <p>Rows are only ever written from an already-valid {@link SagaInstance}, so reading does not re-validate the
 * record's invariants beyond what its own constructor checks (FR-011).</p>
 */
final class SagaInstanceRows {

    /** The {@code saga_instance} columns, in the order {@link #bindInstance} binds them. */
    static final List<String> COLUMNS = List.of("saga_id", "saga_type", "state", "status", "created_at",
            "last_modified_at", "steps", "outcome", "failure_step_name", "failure_cause_type", "failure_message",
            "failure_failed_at", "failure_classification", "failure_attempts", "pending_retry");

    static final String STATE_FIELD = "saga_instance.state";
    static final String STEPS_FIELD = "saga_instance.steps";
    static final String PENDING_RETRY_FIELD = "saga_instance.pending_retry";

    private static final String STATUS = "status";
    private static final String TOUCHED_AGGREGATES = "touchedAggregates";
    private static final String AGGREGATE_TYPE = "aggregateType";
    private static final String AGGREGATE_ID = "aggregateId";
    private static final String ATTEMPTS = "attempts";
    private static final String HANDLED_COMMANDS = "handledCommands";
    private static final String SAGA_ID = "sagaId";
    private static final String STEP_NAME = "stepName";
    private static final String COMMAND_TYPE = "commandType";
    private static final String ORDINAL = "ordinal";
    private static final String VALUE = "value";
    private static final String TRIGGER_STEP = "triggerStep";
    private static final String ATTEMPTS_STARTED = "attemptsStarted";
    private static final String LAST_FAILURE = "lastFailure";
    private static final String CAUSE_TYPE = "causeType";
    private static final String MESSAGE = "message";
    private static final String FAILED_AT = "failedAt";
    private static final String CLASSIFICATION = "classification";

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
     * Binds {@code instance} to parameters 1 to 15, in {@link #COLUMNS} order; {@code state} (3), {@code steps} (7)
     * and {@code pending_retry} (15, or SQL {@code NULL}) are JSON text for {@code ?::jsonb} placeholders. Everything is encoded before the first parameter is
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
        String pendingRetry = encodePendingRetry(instance.pendingRetry());

        statement.setString(1, instance.sagaId());
        statement.setString(2, instance.sagaType());
        statement.setString(3, state);
        statement.setString(4, instance.status().name());
        setInstant(statement, 5, instance.createdAt());
        setInstant(statement, 6, instance.lastModifiedAt());
        statement.setString(7, steps);
        statement.setString(8, instance.outcome());
        bindFailure(statement, 9, instance.failure());
        statement.setString(15, pendingRetry);
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
                failureFromRow(row),
                decodePendingRetry(row.getString("pending_retry")));
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
            step.put(ATTEMPTS, entry.getValue().attempts());
            step.put(HANDLED_COMMANDS, commandsToPlain(entry.getValue().handledCommands()));
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
            int attempts = step.containsKey(ATTEMPTS)
                    ? requireType(Number.class, path + "." + ATTEMPTS, step.get(ATTEMPTS)).intValue()
                    : status == StepStatus.COMPLETED ? 1 : 0;
            List<CommandId> commands = step.containsKey(HANDLED_COMMANDS)
                    ? commandsFromPlain(path + "." + HANDLED_COMMANDS, step.get(HANDLED_COMMANDS))
                    : List.of();
            try {
                steps.put(entry.getKey(), new StepRecord(status, touched, attempts, commands));
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
        statement.setString(firstIndex + 4, failure == null ? null : failure.classification().name());
        if (failure == null) {
            statement.setNull(firstIndex + 5, Types.INTEGER);
        } else {
            statement.setInt(firstIndex + 5, failure.attempts());
        }
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
        String classification = row.getString("failure_classification");
        Object storedAttempts = row.getObject("failure_attempts");
        int attempts = storedAttempts instanceof Number number && number.intValue() >= 1 ? number.intValue() : 1;
        return new SagaFailure(stepName, row.getString("failure_cause_type"), row.getString("failure_message"),
                getInstant(row, "failure_failed_at"),
                classification == null ? FailureClassification.UNCLASSIFIED
                        : parseEnum(FailureClassification.class, "failure_classification", classification),
                attempts);
    }

    // --- handled commands and pending retry (005) ------------------------------------------------------------

    static List<Object> commandsToPlain(List<CommandId> ids) {
        List<Object> plain = new ArrayList<>(ids.size());
        for (CommandId id : ids) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put(SAGA_ID, id.sagaId());
            element.put(STEP_NAME, id.stepName());
            element.put(COMMAND_TYPE, id.commandType());
            element.put(ORDINAL, id.ordinal());
            element.put(VALUE, id.value());
            plain.add(element);
        }
        return plain;
    }

    static List<CommandId> commandsFromPlain(String field, Object plain) {
        List<?> elements = requireType(List.class, field, plain);
        List<CommandId> ids = new ArrayList<>(elements.size());
        int index = 0;
        for (Object element : elements) {
            String path = field + "[" + index + "]";
            Map<?, ?> map = requireType(Map.class, path, element);
            try {
                ids.add(new CommandId(requireType(String.class, path + "." + SAGA_ID, map.get(SAGA_ID)),
                        requireType(String.class, path + "." + STEP_NAME, map.get(STEP_NAME)),
                        requireType(String.class, path + "." + COMMAND_TYPE, map.get(COMMAND_TYPE)),
                        requireType(Number.class, path + "." + ORDINAL, map.get(ORDINAL)).intValue(),
                        requireType(String.class, path + "." + VALUE, map.get(VALUE)), true));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(path + " holds an invalid command identity: " + e.getMessage(), e);
            }
            index++;
        }
        return ids;
    }

    /** @return {@code retry} as JSON text, or {@code null} for SQL {@code NULL} */
    static String encodePendingRetry(PendingRetry retry) {
        if (retry == null) {
            return null;
        }
        SagaFailure failure = retry.lastFailure();
        Map<String, Object> last = new LinkedHashMap<>();
        last.put(STEP_NAME, failure.stepName());
        last.put(CAUSE_TYPE, failure.causeType());
        if (failure.message() != null) {
            last.put(MESSAGE, failure.message());
        }
        last.put(FAILED_AT, failure.failedAt().toString());
        last.put(CLASSIFICATION, failure.classification().name());
        last.put(ATTEMPTS, failure.attempts());
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put(TRIGGER_STEP, retry.triggerStep());
        plain.put(ATTEMPTS_STARTED, retry.attemptsStarted());
        plain.put(LAST_FAILURE, last);
        return JsonCodec.encodeObject(PENDING_RETRY_FIELD, plain);
    }

    /** @return the pending retry stored as {@code json}, or {@code null} for SQL {@code NULL} */
    static PendingRetry decodePendingRetry(String json) {
        if (json == null) {
            return null;
        }
        String path = PENDING_RETRY_FIELD;
        Map<String, Object> plain = JsonCodec.decodeObject(path, json);
        Map<?, ?> last = requireType(Map.class, path + "." + LAST_FAILURE, plain.get(LAST_FAILURE));
        String lastPath = path + "." + LAST_FAILURE;
        try {
            SagaFailure failure = new SagaFailure(
                    requireType(String.class, lastPath + "." + STEP_NAME, last.get(STEP_NAME)),
                    requireType(String.class, lastPath + "." + CAUSE_TYPE, last.get(CAUSE_TYPE)),
                    last.get(MESSAGE) == null ? null
                            : requireType(String.class, lastPath + "." + MESSAGE, last.get(MESSAGE)),
                    Instant.parse(requireType(String.class, lastPath + "." + FAILED_AT, last.get(FAILED_AT))),
                    parseEnum(FailureClassification.class, lastPath + "." + CLASSIFICATION,
                            requireType(String.class, lastPath + "." + CLASSIFICATION, last.get(CLASSIFICATION))),
                    requireType(Number.class, lastPath + "." + ATTEMPTS, last.get(ATTEMPTS)).intValue());
            return new PendingRetry(requireType(String.class, path + "." + TRIGGER_STEP, plain.get(TRIGGER_STEP)),
                    requireType(Number.class, path + "." + ATTEMPTS_STARTED, plain.get(ATTEMPTS_STARTED)).intValue(),
                    failure);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new IllegalStateException(path + " holds an invalid pending retry: " + e.getMessage(), e);
        }
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
