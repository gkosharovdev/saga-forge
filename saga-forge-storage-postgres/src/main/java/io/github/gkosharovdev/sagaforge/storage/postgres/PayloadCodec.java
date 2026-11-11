package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.log.DeliveryFailure;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON encoding of message-log payloads and their metadata ({@code specs/007-in-memory-transport} research §7).
 * Unlike {@link JsonCodec}, which keeps saga state to a closed value set, payloads are the application's own event and
 * command types: records, or classes with fields and a no-argument constructor. Fields are read and written directly,
 * whatever their visibility; unknown properties are ignored when reading.
 */
final class PayloadCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultVisibility(visibility -> visibility
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private PayloadCodec() {
    }

    /** @throws IllegalArgumentException if {@code payload} cannot be encoded */
    static String encode(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("payload of type " + payload.getClass().getName()
                    + " cannot be encoded as JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * @throws ClassNotFoundException if {@code typeName} cannot be loaded
     * @throws IllegalStateException  if {@code json} cannot be decoded into it
     */
    static Object decode(String typeName, String json, ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> type = Class.forName(typeName, false, classLoader);
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("payload cannot be decoded as " + typeName + ": " + e.getOriginalMessage(),
                    e);
        }
    }

    static String encodeCommandId(CommandId id) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sagaId", id.sagaId());
        fields.put("stepName", id.stepName());
        fields.put("commandType", id.commandType());
        fields.put("ordinal", id.ordinal());
        fields.put("value", id.value());
        fields.put("sagaScoped", id.sagaScoped());
        return MAPPER.writeValueAsString(fields);
    }

    static CommandId decodeCommandId(String json) {
        Map<?, ?> fields = MAPPER.readValue(json, Map.class);
        return new CommandId((String) fields.get("sagaId"), (String) fields.get("stepName"),
                (String) fields.get("commandType"), ((Number) fields.get("ordinal")).intValue(),
                (String) fields.get("value"), (Boolean) fields.get("sagaScoped"));
    }

    static String encodeFailure(DeliveryFailure failure) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("exceptionType", failure.exceptionType());
        fields.put("message", failure.message());
        fields.put("at", failure.at().toString());
        return MAPPER.writeValueAsString(fields);
    }

    static DeliveryFailure decodeFailure(String json) {
        Map<?, ?> fields = MAPPER.readValue(json, Map.class);
        return new DeliveryFailure((String) fields.get("exceptionType"), (String) fields.get("message"),
                Instant.parse((String) fields.get("at")));
    }
}
