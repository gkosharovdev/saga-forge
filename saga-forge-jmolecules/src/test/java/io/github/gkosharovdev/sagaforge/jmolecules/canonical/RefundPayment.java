package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import java.math.BigDecimal;
import org.jmolecules.architecture.cqrs.Command;

@Command
public record RefundPayment(String sagaId, String paymentId, BigDecimal amount) {}
