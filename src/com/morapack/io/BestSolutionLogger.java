package com.morapack.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Utility to append best GA solutions into a single CSV file with a stable header.
 */
public final class BestSolutionLogger implements Closeable {
    private static final String HEADER = "timestampSim,generacion,idPedido,fitness,costoTotal,tiempoTotalMin,distTotalKm,violacionesSLA,violacionesCap,escalasProm,ocupacionProm,ruta";

    private final String outputPath;
    private Path path;
    private PrintWriter writer;
    private boolean headerWritten;

    public BestSolutionLogger(String outputPath) {
        this.outputPath = outputPath != null && !outputPath.isBlank() ? outputPath : "out/best_solutions.csv";
    }

    /**
     * Opens the CSV file in append mode, creating directories and header if needed.
     */
    public synchronized void open() throws IOException {
        if (writer != null) {
            return;
        }
        path = Paths.get(outputPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean existed = Files.exists(path);
        long size = existed ? Files.size(path) : 0L;
        writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        headerWritten = size > 0L;
        System.out.println("[BestSolutionLogger] writing to " + path);
        if (!headerWritten) {
            writer.println(HEADER);
            writer.flush();
            headerWritten = true;
        }
    }

    /**
     * Appends a CSV row describing the best individual of a generation.
     */
    public synchronized void logRow(long tSim, int generation, String idPedido, double fitness,
                                    double costoTotal, double tiempoMin, double distKm,
                                    int violSla, int violCap, double escalasProm,
                                    double ocupacionProm, String rutaSerializada) {
        if (writer == null) {
            throw new IllegalStateException("Logger not opened");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tSim).append(',')
          .append(generation).append(',')
          .append(escape(idPedido)).append(',')
          .append(fitness).append(',')
          .append(costoTotal).append(',')
          .append(tiempoMin).append(',')
          .append(distKm).append(',')
          .append(violSla).append(',')
          .append(violCap).append(',')
          .append(escalasProm).append(',')
          .append(ocupacionProm).append(',')
          .append(escape(rutaSerializada));
        writer.println(sb);
        writer.flush();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuotes) {
            return value;
        }
        String escaped = value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }
}
