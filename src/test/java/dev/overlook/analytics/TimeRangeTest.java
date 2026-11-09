package dev.overlook.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.MutableClock;
import dev.overlook.vercel.model.Granularity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:30:00Z"));

    @Test
    void sevenDaysEndsWithOpenBucket() {
        TimeRange r = TimeRange.parse("7d", null, null, clock);

        assertThat(r.granularity()).isEqualTo(Granularity.DAY);
        assertThat(r.since()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(r.buckets()).hasSize(7);
        assertThat(r.closedBuckets()).isEqualTo(6);
        assertThat(r.isOpen(Instant.parse("2026-10-08T00:00:00Z"))).isTrue();
        assertThat(r.closedUntil()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Test
    void previousPeriodHasSameBucketsAllClosed() {
        TimeRange prev = TimeRange.parse("7d", null, null, clock).previous();

        assertThat(prev.since()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
        assertThat(prev.until()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(prev.buckets()).hasSize(7);
        assertThat(prev.closedBuckets()).isEqualTo(7);
    }

    @Test
    void last24hIsHourly() {
        TimeRange r = TimeRange.parse("24h", null, null, clock);

        assertThat(r.granularity()).isEqualTo(Granularity.HOUR);
        assertThat(r.buckets()).hasSize(24);
        assertThat(r.closedBuckets()).isEqualTo(23);
    }

    @Test
    void customRangeIsInclusiveAndCappedAtNow() {
        TimeRange r = TimeRange.parse("custom", "2026-09-01", "2026-10-31", clock);

        assertThat(r.since()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(r.until()).isEqualTo(clock.instant());
        assertThat(r.buckets()).hasSize(38);

        TimeRange closed = TimeRange.parse(null, "2026-09-01", "2026-09-30", clock);
        assertThat(closed.buckets()).hasSize(30);
        assertThat(closed.closedBuckets()).isEqualTo(30);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> TimeRange.parse("1y", null, null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeRange.parse(null, "ontem", null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeRange.parse(null, "2026-10-05", "2026-10-01", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
