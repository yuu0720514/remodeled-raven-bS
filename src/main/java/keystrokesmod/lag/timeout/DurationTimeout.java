package keystrokesmod.lag.timeout;

import keystrokesmod.lag.timeout.AbstractTimeout;

public final class DurationTimeout
extends AbstractTimeout {
    private final long durationMs;
    private final long startTimeMs;

    public DurationTimeout(long durationMs) {
        this.durationMs = Math.max(0L, durationMs);
        this.startTimeMs = System.currentTimeMillis();
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - this.startTimeMs;
    }

    public long remainingMs() {
        return Math.max(0L, this.durationMs - this.elapsedMs());
    }

    @Override
    protected boolean shouldHaveTimedOut() {
        return this.durationMs <= 0L || this.elapsedMs() >= this.durationMs;
    }
}

