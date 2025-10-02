package com.morapack.ga.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvPlanLogger implements PlanLogger, Closeable {
    private final PrintWriter metricsWriter;

    public CsvPlanLogger(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        this.metricsWriter = new PrintWriter(Files.newBufferedWriter(outDir.resolve("metrics_ga.csv")));
        this.metricsWriter.println("t,key,value");
        this.metricsWriter.flush();
    }

    @Override
    public void logAssignment(Object a) {
        // TODO: integrate with plan_asignacion_GA.csv when schema is available
    }

    @Override
    public void logMetric(String key, double value, long t) {
        metricsWriter.println(t + "," + key + "," + value);
    }

    @Override
    public void close() {
        if (metricsWriter != null) {
            metricsWriter.flush();
            metricsWriter.close();
        }
    }
}
