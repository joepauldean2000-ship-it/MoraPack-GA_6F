package com.morapack.io;

import com.morapack.ga.Trigger;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Logger CSV para eventos de replanificación. */
public final class ReplanEventLogger implements AutoCloseable {
    private final Path path;
    private PrintWriter writer;
    private boolean headerWritten;

    public ReplanEventLogger(Path path) {
        this.path = path;
    }

    public void open() throws IOException {
        Files.createDirectories(path.getParent());
        boolean exists = Files.exists(path);
        writer = new PrintWriter(Files.newBufferedWriter(path,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        boolean writeHeader;
        if (!exists) {
            writeHeader = true;
        } else {
            writeHeader = Files.size(path) == 0;
        }
        if (writeHeader) {
            writer.println("timestampSim,trigger,batch,pedidosCancelados,freed,moved,backlog,vuelosColapsados");
        }
        headerWritten = true;
        writer.flush();
    }

    public synchronized void log(long timestampSim, Trigger trigger, int batchIndex,
                                  int pedidosCancelados, int freed, int moved, int backlog,
                                  int vuelosColapsados) {
        if (writer == null) {
            return;
        }
        if (!headerWritten) {
            writer.println("timestampSim,trigger,batch,pedidosCancelados,freed,moved,backlog,vuelosColapsados");
            headerWritten = true;
        }
        writer.printf(Locale.US, "%d,%s,%d,%d,%d,%d,%d,%d%n", timestampSim,
                trigger != null ? trigger.name() : "NA", batchIndex,
                Math.max(0, pedidosCancelados), Math.max(0, freed), Math.max(0, moved),
                Math.max(0, backlog), Math.max(0, vuelosColapsados));
        writer.flush();
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
        }
    }
}
