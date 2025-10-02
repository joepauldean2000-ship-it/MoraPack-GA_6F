package com.morapack.ga.core;

public final class NoopPlanLogger implements PlanLogger {
    @Override
    public void logAssignment(Object a) {
    }

    @Override
    public void logMetric(String key, double value, long t) {
    }
}
