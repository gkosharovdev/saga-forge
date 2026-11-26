package io.github.gkosharovdev.sagaforge.core.spi.store;

/**
 * A {@link MessageStore#lease} call with a {@link LeaderLease} that is no longer the store's
 * current, unexpired leadership: another relay took over, or the lease ran out. Nothing was leased;
 * the caller must stop leasing and become standby ({@code specs/013-active-standby-relay} FR-008).
 */
public class LeadershipLostException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient LeaderLease staleLease;

  /**
   * @param staleLease the lease the caller still held
   */
  public LeadershipLostException(LeaderLease staleLease) {
    super(
        "Relay "
            + staleLease.holderId()
            + " no longer holds the leadership of the message store (fencing token "
            + staleLease.fencingToken()
            + ")");
    this.staleLease = staleLease;
  }

  /**
   * @return the lease the caller still held
   */
  public LeaderLease staleLease() {
    return staleLease;
  }
}
