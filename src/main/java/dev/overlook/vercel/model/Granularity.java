package dev.overlook.vercel.model;

import java.time.Duration;

public enum Granularity {
    HOUR("1h", Duration.ofHours(1)),
    DAY("1d", Duration.ofDays(1));

    private final String flag;
    private final Duration duration;

    Granularity(String flag, Duration duration) {
        this.flag = flag;
        this.duration = duration;
    }

    public String flag() {
        return flag;
    }

    public Duration duration() {
        return duration;
    }
}
