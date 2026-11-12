package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Identifies one aggregate touched by a completed saga step.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if either component is {@code null}.</p>
 *
 * @param aggregateType fully qualified name of the {@code @Aggregate} class named by the domain event's
 *                      {@code aggregate} attribute; non-null
 * @param aggregateId   the domain event's {@code @AggregateIdentifier} value, as {@link String#valueOf(Object)};
 *                      non-null
 */
public record AggregateReference(String aggregateType, String aggregateId) {

    public AggregateReference {
        if (aggregateType == null) {
            throw new IllegalArgumentException("aggregateType must not be null");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId must not be null");
        }
    }

    /**
     * Creates a reference from an aggregate class and its identifier value.
     *
     * @param type the {@code @Aggregate} class; non-null
     * @param id   the identifier value, converted with {@link String#valueOf(Object)}; non-null
     * @return the reference
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public static AggregateReference of(Class<?> type, Object id) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        return new AggregateReference(type.getName(), String.valueOf(id));
    }
}
