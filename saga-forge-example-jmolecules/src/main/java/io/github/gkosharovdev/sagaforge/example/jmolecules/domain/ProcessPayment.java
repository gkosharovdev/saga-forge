package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.math.BigDecimal;
import org.jmolecules.architecture.cqrs.Command;

/** Charge the order's amount. */
@Command
public record ProcessPayment(OrderId orderId, BigDecimal amount) {}
