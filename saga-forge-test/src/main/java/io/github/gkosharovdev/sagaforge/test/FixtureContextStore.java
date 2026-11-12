package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;

/**
 * The fixture's {@code SagaContext} values, in memory. Like every context store it never joins the unit of work's
 * transaction, so values written by a rolled-back attempt stay. With the value check on, a value durable storage
 * cannot persist is rejected with {@link UnsupportedContextValueException} (FR-019).
 */
final class FixtureContextStore implements SagaContextStore {

    private final Map<String, SagaContextRecord> records = new LinkedHashMap<>();
    private final List<UnsupportedContextValueException> rejections = new ArrayList<>();
    private final boolean checkValues;
    private final Function<String, String> sagaTypeOf;

    /**
     * @param checkValues whether to reject values durable storage cannot persist
     * @param sagaTypeOf  the saga type of a saga id, for the rejection's message
     */
    FixtureContextStore(boolean checkValues, Function<String, String> sagaTypeOf) {
        this.checkValues = checkValues;
        this.sagaTypeOf = sagaTypeOf;
    }

    @Override
    public synchronized Optional<SagaContextRecord> find(String sagaId) {
        requireSagaId(sagaId);
        return Optional.ofNullable(records.get(sagaId));
    }

    @Override
    public synchronized void save(SagaContextRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (checkValues) {
            Optional<DurableContextValues.Violation> violation = DurableContextValues.firstViolation(record.values());
            if (violation.isPresent()) {
                UnsupportedContextValueException rejected = new UnsupportedContextValueException(
                        sagaTypeOf.apply(record.sagaId()), record.sagaId(), violation.get().path(),
                        violation.get().description());
                rejections.add(rejected);
                throw rejected;
            }
        }
        records.put(record.sagaId(), record);
    }

    @Override
    public synchronized void delete(String sagaId) {
        requireSagaId(sagaId);
        records.remove(sagaId);
    }

    /** The values of {@code sagaId}; empty if it has none. */
    synchronized Map<String, Object> values(String sagaId) {
        SagaContextRecord record = records.get(sagaId);
        return record == null ? Map.of() : record.values();
    }

    /** Takes the rejections since the last call. */
    synchronized List<UnsupportedContextValueException> takeRejections() {
        List<UnsupportedContextValueException> taken = List.copyOf(rejections);
        rejections.clear();
        return taken;
    }

    private static void requireSagaId(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
    }
}
