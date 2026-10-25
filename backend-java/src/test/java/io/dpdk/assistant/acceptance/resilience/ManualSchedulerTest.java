package io.dpdk.assistant.acceptance.resilience;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.task.TaskSettings;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManualSchedulerTest {
    @Test void default600SecondsArePreservedAndDoNotFireAt599() throws Exception {
        var settings = TaskSettings.defaults();
        assertThat(settings.ragTaskMaxSeconds()).isEqualTo(Duration.ofSeconds(600));
        assertThat(settings.inputTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(settings.finishedTtl()).isEqualTo(Duration.ofSeconds(300));
        var scheduler = new ManualScheduler();
        var calls = new AtomicInteger();
        var future = scheduler.schedule(calls::incrementAndGet, settings.ragTaskMaxSeconds().toNanos(), TimeUnit.NANOSECONDS);
        scheduler.advance(Duration.ofSeconds(599));
        assertThat(calls).hasValue(0); assertThat(future.isDone()).isFalse();
        scheduler.advance(Duration.ofMillis(1500));
        assertThat(calls).hasValue(1); assertThat(future.get()).isEqualTo(1);
    }
    @Test void cancelledWatchDoesNotFireAndClockCannotBacktrack() {
        var scheduler = new ManualScheduler();
        var count = new AtomicInteger();
        var cancelled = scheduler.schedule(count::incrementAndGet, 600, TimeUnit.SECONDS);
        cancelled.cancel(false);
        scheduler.advance(Duration.ofHours(24));
        assertThat(count).hasValue(0);
        assertThatThrownBy(() -> scheduler.advance(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
