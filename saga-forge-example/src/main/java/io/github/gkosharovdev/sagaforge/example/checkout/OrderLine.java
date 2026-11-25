package io.github.gkosharovdev.sagaforge.example.checkout;

/**
 * One line of an order: a product and how many of it. A value object; commands and events carry it,
 * the {@code SagaContext} does not (it keeps JSON scalars only).
 */
public record OrderLine(String sku, int quantity) {

  public OrderLine {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }
}
