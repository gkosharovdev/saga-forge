package io.github.gkosharovdev.sagaforge.example.events;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

@DomainEvent
public record OrderCreatedEvent(String orderId) {
}
