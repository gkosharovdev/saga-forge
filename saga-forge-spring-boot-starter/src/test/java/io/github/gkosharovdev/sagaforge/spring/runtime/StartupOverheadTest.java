package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;

/**
 * Startup overhead of the starter for a 20-saga model ({@code specs/008-spring-boot-starter} SC-005): context start
 * with the starter and the scanned model, against the same context with Saga Forge switched off; median of 5 runs
 * each, after a warm-up.
 */
class StartupOverheadTest {

    private static final String MODEL = "io.github.gkosharovdev.sagaforge.spring.fixtures.overhead";

    private static long median(ApplicationContextRunner runner, boolean expectRuntime) {
        List<Long> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long start = System.nanoTime();
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                if (expectRuntime) {
                    assertThat(context.getBean(SagaRuntime.class).isStarted()).isTrue();
                }
            });
            if (i > 0) {
                runs.add(System.nanoTime() - start);
            }
        }
        runs.sort(Long::compare);
        return runs.get(runs.size() / 2);
    }

    @Test
    @DisplayName("a 20-saga model adds at most one second to startup")
    void overhead() {
        ApplicationContextRunner runner = StarterRunner.withDoubles(StarterRunner.runner())
                .withPropertyValues("saga-forge.scan-packages=" + MODEL);
        long with = median(runner, true);
        long without = median(runner.withPropertyValues("saga-forge.enabled=false"), false);

        Duration overhead = Duration.ofNanos(Math.max(0, with - without));
        System.out.println("saga-forge startup overhead for 20 sagas: " + overhead.toMillis() + " ms");
        assertThat(overhead).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }
}
