package com.morapack.ga.core;

public interface SimClock {
    long now();
    void advanceTo(long t);
}
