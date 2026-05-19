package com.risksentinel.core.ops;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsRegistryTest {

    @Test
    void tagsOf_shouldRejectOddNumberOfArguments() {
        assertThatThrownBy(() -> Tags.of("k1", "v1", "k2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsOf_shouldPreserveInsertionOrder() {
        Tags t = Tags.of("b", "2", "a", "1");
        assertThat(t.asMap().keySet()).containsExactly("b", "a");
    }

    @Test
    void tagsEmpty_shouldBeReusedSingleton() {
        assertThat(Tags.empty()).isSameAs(Tags.empty());
        assertThat(Tags.of()).isSameAs(Tags.empty());
    }

    @Test
    void noopRegistry_shouldCountIncrementsLocally() {
        MetricsRegistry m = new NoopMetricsRegistry();
        Counter c = m.counter("foo", Tags.empty());

        c.increment();
        c.increment(4);

        assertThat(c.count()).isEqualTo(5);
        assertThat(m.scrapeText()).isEmpty();
    }

    @Test
    void noopRegistry_shouldReturnSameCounter_forSameNameAndTags() {
        MetricsRegistry m = new NoopMetricsRegistry();
        Counter a = m.counter("foo", Tags.of("k", "v"));
        Counter b = m.counter("foo", Tags.of("k", "v"));

        a.increment();
        a.increment();
        assertThat(b.count()).isEqualTo(2);
        assertThat(b).isSameAs(a);
    }

    @Test
    void noopRegistry_shouldDistinguishByTags() {
        MetricsRegistry m = new NoopMetricsRegistry();
        Counter accept = m.counter("foo", Tags.of("d", "accept"));
        Counter reject = m.counter("foo", Tags.of("d", "reject"));

        accept.increment();
        reject.increment();
        reject.increment();

        assertThat(accept.count()).isEqualTo(1);
        assertThat(reject.count()).isEqualTo(2);
    }

    @Test
    void noopRegistry_timerCountsCalls() {
        MetricsRegistry m = new NoopMetricsRegistry();
        Timer t = m.timer("bar", Tags.empty());

        t.recordNanos(1_000);
        t.recordNanos(2_000);

        assertThat(t.count()).isEqualTo(2);
    }

    @Test
    void noopRegistry_gaugeReadsSupplier() {
        MetricsRegistry m = new NoopMetricsRegistry();
        AtomicLong source = new AtomicLong(42);
        Gauge g = m.gauge("baz", Tags.empty(), source::get);

        assertThat(g.value()).isEqualTo(42.0);
        source.set(99);
        assertThat(g.value()).isEqualTo(99.0);
    }

    @Test
    void micrometerRegistry_shouldExposePrometheusFormat() {
        MicrometerMetricsRegistry m = new MicrometerMetricsRegistry();
        Counter c = m.counter("paper_broker_submitted_total", Tags.empty());
        c.increment();
        c.increment();
        c.increment();

        String scrape = m.scrapeText();
        assertThat(scrape).contains("paper_broker_submitted_total");
        assertThat(scrape).contains("3.0");
        assertThat(c.count()).isEqualTo(3);
    }

    @Test
    void micrometerRegistry_shouldRenderTagsAsLabels() {
        MicrometerMetricsRegistry m = new MicrometerMetricsRegistry();
        m.counter("gateway_decide_total", Tags.of("decision", "accept", "code", "none")).increment();
        m.counter("gateway_decide_total", Tags.of("decision", "reject", "code", "FAT_FINGER_QUANTITY")).increment();

        String scrape = m.scrapeText();
        assertThat(scrape).contains("decision=\"accept\"");
        assertThat(scrape).contains("decision=\"reject\"");
        assertThat(scrape).contains("code=\"FAT_FINGER_QUANTITY\"");
    }

    @Test
    void micrometerRegistry_shouldDeduplicateCounter_perNameAndTags() {
        MicrometerMetricsRegistry m = new MicrometerMetricsRegistry();
        Counter a = m.counter("foo_total", Tags.of("k", "v"));
        Counter b = m.counter("foo_total", Tags.of("k", "v"));

        a.increment();
        b.increment();
        assertThat(a.count()).isEqualTo(2);
        assertThat(b.count()).isEqualTo(2);
    }

    @Test
    void micrometerRegistry_timerRecordsNanos() {
        MicrometerMetricsRegistry m = new MicrometerMetricsRegistry();
        Timer t = m.timer("foo_seconds", Tags.empty());
        t.recordNanos(1_000_000L);
        t.recordNanos(2_000_000L);

        assertThat(t.count()).isEqualTo(2);
        assertThat(m.scrapeText()).contains("foo_seconds_count");
    }
}
