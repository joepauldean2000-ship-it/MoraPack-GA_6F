package com.morapack.ga;

public final class GaStats {
    public final int gen;
    public final double best;
    public final double avg;
    public final double worst;
    public final double feasibleRatio;
    public final double mutationRate;
    public final double diversityIndex;

    public GaStats(int gen, double best, double avg, double worst,
                   double feasibleRatio, double mutationRate, double diversityIndex) {
        this.gen = gen;
        this.best = best;
        this.avg = avg;
        this.worst = worst;
        this.feasibleRatio = feasibleRatio;
        this.mutationRate = mutationRate;
        this.diversityIndex = diversityIndex;
    }
}
