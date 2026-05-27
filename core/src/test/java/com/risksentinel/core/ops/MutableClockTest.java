package com.risksentinel.core.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

    private static final Instant T0 = Instant.parse("2026-05-19T12:00:00Z");

    @Test
    void shouldReturnInitialInstant() {
        MutableClock c = new MutableClock(T0);
        assertThat(c.instant()).isEqualTo(T0);
    }

    @Test
    void shouldReflectSetNow() {
        MutableClock c = new MutableClock(T0);
        c.setNow(T0.plusSeconds(60));
        assertThat(c.instant()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void shouldExposeUtcZone() {
        MutableClock c = new MutableClock(T0);
        assertThat(c.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void shouldReturnSelf_fromWithZone() {
        MutableClock c = new MutableClock(T0);
        assertThat(c.withZone(ZoneId.of("America/New_York"))).isSameAs(c);
    }

    @Test
    void shouldPropagateWritesAcrossThreads() throws Exception {
        MutableClock c = new MutableClock(T0);
        Instant target = T0.plusSeconds(123);
        Thread writer = new Thread(() -> c.setNow(target));
        writer.start();
        writer.join();
        assertThat(c.instant()).isEqualTo(target);
    }
}
