package com.morapack.ga.core;

public final class EventDrivenClock implements SimClock {
    private long t;

    public EventDrivenClock(long t0) {
        this.t = t0;
    }

    @Override
    public long now() {
        return t;
    }

    @Override
    public void advanceTo(long t) {
        this.t = Math.max(this.t, t);
    }
}
