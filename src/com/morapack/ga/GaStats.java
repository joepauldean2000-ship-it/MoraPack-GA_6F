package com.morapack.ga;

public final class GaStats {
    public final int gen;
    public final double best;
    public final double avg;
    public final double worst;
    public final double feasibleRatio;
    public final double mutationRate;
    public final double diversityIndex;
    public final double p95Transit;
    public final double p95Wait;
    public final double p95Cost;
    public final int maxHopsRef;

    public GaStats(int gen, double best, double avg, double worst,
                   double feasibleRatio, double mutationRate, double diversityIndex,
                   double p95Transit, double p95Wait, double p95Cost, int maxHopsRef) {
        this.gen = gen;
        this.best = best;
        this.avg = avg;
        this.worst = worst;
        this.feasibleRatio = feasibleRatio;
        this.mutationRate = mutationRate;
        this.diversityIndex = diversityIndex;
        this.p95Transit = p95Transit;
        this.p95Wait = p95Wait;
        this.p95Cost = p95Cost;
        this.maxHopsRef = maxHopsRef;
    }
}
