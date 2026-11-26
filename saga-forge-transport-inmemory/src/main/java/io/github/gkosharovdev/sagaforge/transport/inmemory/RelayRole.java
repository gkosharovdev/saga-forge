package io.github.gkosharovdev.sagaforge.transport.inmemory;

/** The role of a transport's relay among the instances sharing one message store (013). */
public enum RelayRole {
  /** Holds the store's leadership and delivers messages. */
  ACTIVE,
  /** Delivers nothing; takes over when the active relay stops or its leadership expires. */
  STANDBY
}
