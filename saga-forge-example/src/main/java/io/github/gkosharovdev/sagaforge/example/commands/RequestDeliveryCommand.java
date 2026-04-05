package io.github.gkosharovdev.sagaforge.example.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

@Command
public record RequestDeliveryCommand(String orderId) {
}
