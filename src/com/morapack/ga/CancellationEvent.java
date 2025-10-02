package com.morapack.ga;

final class CancellationEvent implements Comparable<CancellationEvent> {
    final long timestampSim;
    final String pedidoId;
    final String motivo;
    final String fuente;

    CancellationEvent(long timestampSim, String pedidoId, String motivo, String fuente) {
        this.timestampSim = timestampSim;
        this.pedidoId = pedidoId;
        this.motivo = motivo;
        this.fuente = fuente;
    }

    String key() {
        return timestampSim + "#" + (pedidoId != null ? pedidoId : "");
    }

    @Override
    public int compareTo(CancellationEvent other) {
        if (other == null) {
            return -1;
        }
        int cmp = Long.compare(this.timestampSim, other.timestampSim);
        if (cmp != 0) {
            return cmp;
        }
        if (this.pedidoId == null && other.pedidoId == null) {
            return 0;
        }
        if (this.pedidoId == null) {
            return -1;
        }
        if (other.pedidoId == null) {
            return 1;
        }
        return this.pedidoId.compareTo(other.pedidoId);
    }
}
