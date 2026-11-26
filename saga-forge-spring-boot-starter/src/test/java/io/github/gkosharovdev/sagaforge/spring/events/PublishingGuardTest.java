package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PublishingGuard} (specs/014-publish-once research R3). */
class PublishingGuardTest {

  @Test
  @DisplayName("active only inside run, nested runs restore the outer value")
  void nesting() {
    List<Boolean> seen = new ArrayList<>();

    assertThat(PublishingGuard.active()).isFalse();
    PublishingGuard.run(
        () -> {
          seen.add(PublishingGuard.active());
          PublishingGuard.run(() -> seen.add(PublishingGuard.active()));
          seen.add(PublishingGuard.active());
        });

    assertThat(seen).containsExactly(true, true, true);
    assertThat(PublishingGuard.active()).isFalse();
  }

  @Test
  @DisplayName("cleared when the publication throws")
  void clearedOnFailure() {
    assertThatThrownBy(
            () ->
                PublishingGuard.run(
                    () -> {
                      throw new IllegalStateException("listener failed");
                    }))
        .hasMessage("listener failed");
    assertThat(PublishingGuard.active()).isFalse();
  }
}
