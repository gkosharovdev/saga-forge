package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import java.math.BigDecimal;
import org.jmolecules.architecture.cqrs.Command;

@Command
public record ProcessPayment(OrderId orderId, BigDecimal amount) {}
