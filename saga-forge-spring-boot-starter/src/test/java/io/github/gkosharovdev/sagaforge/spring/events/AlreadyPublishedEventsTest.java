package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link AlreadyPublishedEvents} (specs/014-publish-once research R2). */
class AlreadyPublishedEventsTest {

  private record Event(String id) {}

  @AfterEach
  void cleanUp() {
    AlreadyPublishedEvents.clear();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("held by identity, not equality")
  void identity() {
    Event noted = new Event("e-1");

    AlreadyPublishedEvents.add(noted);

    assertThat(AlreadyPublishedEvents.contains(noted)).isTrue();
    assertThat(AlreadyPublishedEvents.contains(new Event("e-1"))).isFalse();
  }

  @Test
  @DisplayName("cleared after the Spring transaction completes, committed or rolled back")
  void clearedAfterCompletion() {
    for (int status :
        new int[] {
          TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK
        }) {
      TransactionSynchronizationManager.initSynchronization();
      Event noted = new Event("e-" + status);
      AlreadyPublishedEvents.add(noted);

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(synchronization -> synchronization.afterCompletion(status));
      TransactionSynchronizationManager.clearSynchronization();

      assertThat(AlreadyPublishedEvents.contains(noted)).isFalse();
    }
  }
}
