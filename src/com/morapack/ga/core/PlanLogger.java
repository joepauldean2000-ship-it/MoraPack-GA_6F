package com.morapack.ga.core;

// import com.morapack.ga.Asignacion; // TODO: integrate when available

public interface PlanLogger {
    void logAssignment(Object a);
    void logMetric(String key, double value, long t);
}
