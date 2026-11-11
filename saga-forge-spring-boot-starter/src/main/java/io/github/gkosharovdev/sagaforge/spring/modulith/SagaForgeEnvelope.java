package io.github.gkosharovdev.sagaforge.spring.modulith;

/**
 * What Saga Forge stores in Spring Modulith's event publication registry for one message
 * ({@code specs/007-in-memory-transport} FR-028a): the identity, kind and type of the message, and its payload as
 * serialized by the registry's own {@code EventSerializer}. Saga Forge's delivery record beside it holds everything
 * else.
 *
 * @param messageId   the store's message identity
 * @param kind        {@code EVENT} or {@code COMMAND}
 * @param payloadType fully qualified class name of the payload
 * @param payload     the payload in the registry serializer's form
 */
public record SagaForgeEnvelope(String messageId, String kind, String payloadType, String payload) {
}
