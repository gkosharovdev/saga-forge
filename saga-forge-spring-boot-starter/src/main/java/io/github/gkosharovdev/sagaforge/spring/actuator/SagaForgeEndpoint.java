package io.github.gkosharovdev.sagaforge.spring.actuator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;

/**
 * Read-only operator view of the message store ({@code specs/008-spring-boot-starter} FR-020, contracts/actuator.md):
 * message counts per status, capped at 1,000 each, and the age of the oldest unconsumed message. No write operation
 * (G-008-02).
 */
@Endpoint(id = "sagaforge")
public class SagaForgeEndpoint {

    private final MessageStoreQueries queries;

    public SagaForgeEndpoint(MessageStoreQueries queries) {
        this.queries = queries;
    }

    @ReadOperation
    public Map<String, Object> status() {
        MessageStoreQueries.Status status = queries.status();
        Map<String, Integer> counts = new TreeMap<>();
        status.counts().forEach((messageStatus, count) -> counts.put(messageStatus.name(), count));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("counts", counts);
        status.oldestUnconsumed().ifPresent(age -> payload.put("oldestUnconsumed", age.toString()));
        return payload;
    }
}
