package io.github.gkosharovdev.sagaforge.spring.events;

/**
 * Marks the calling thread while Saga Forge publishes an event to Spring, so that {@link
 * InboundSagaEventListener} does not take Saga Forge's own publications back in ({@code
 * specs/014-publish-once} research R3, contracts/inbound-route.md IR1). Spring's synchronous
 * listeners run inside {@code publishEvent} on the same thread, so a thread-bound flag around the
 * call is exact.
 */
final class PublishingGuard {

  private static final ThreadLocal<Boolean> PUBLISHING = new ThreadLocal<>();

  private PublishingGuard() {}

  /**
   * Runs {@code publication} with the flag set, then restores the previous value, also when it
   * throws.
   */
  static void run(Runnable publication) {
    Boolean previous = PUBLISHING.get();
    PUBLISHING.set(Boolean.TRUE);
    try {
      publication.run();
    } finally {
      if (previous == null) {
        PUBLISHING.remove();
      } else {
        PUBLISHING.set(previous);
      }
    }
  }

  /**
   * @return {@code true} while Saga Forge publishes to Spring on the calling thread
   */
  static boolean active() {
    return Boolean.TRUE.equals(PUBLISHING.get());
  }
}
