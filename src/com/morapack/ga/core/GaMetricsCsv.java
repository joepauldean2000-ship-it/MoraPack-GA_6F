package com.morapack.ga.core;

import com.morapack.ga.GaStats;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GaMetricsCsv implements Closeable, Flushable {
    private final PrintWriter pw;
    private boolean headerWritten = false;

    public GaMetricsCsv(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.pw = new PrintWriter(Files.newBufferedWriter(path));
    }

    public void logGaStats(GaStats stats) {
        if (!headerWritten) {
            pw.println("gen,best,avg,worst,feasible,mutationRate,diversityIndex,p95Transit,p95Wait,p95Cost,maxHopsRef");
            headerWritten = true;
        }
        pw.println(stats.gen + "," + stats.best + "," + stats.avg + "," + stats.worst
                + "," + stats.feasibleRatio + "," + stats.mutationRate + "," + stats.diversityIndex
                + "," + stats.p95Transit + "," + stats.p95Wait + "," + stats.p95Cost + "," + stats.maxHopsRef);
    }

    @Override
    public void flush() {
        pw.flush();
    }

    @Override
    public void close() {
        pw.flush();
        pw.close();
    }
}
