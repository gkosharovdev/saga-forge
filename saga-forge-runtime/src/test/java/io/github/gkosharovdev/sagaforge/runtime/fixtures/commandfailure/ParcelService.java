package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/** Handles the parcel commands; the 012 tests report their failures instead of delivering them. */
public final class ParcelService {

  @CommandHandler
  public void ship(ShipParcel command) {
    // Delivered only by tests that use a real transport.
  }

  @CommandHandler
  public void refund(RefundParcel command) {
    // Delivered only by tests that use a real transport.
  }
}
