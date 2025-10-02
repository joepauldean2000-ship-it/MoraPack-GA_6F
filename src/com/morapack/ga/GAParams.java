package com.morapack.ga;

public final class GAParams {
    public int popSize, generations, tournamentK;
    public double pCrossover, pMutation, elitism;
    public boolean batchingEnabled = true;
    public long batchWindowMin = 120;
    public boolean replanEnabled = true;
    public long replanEveryMin = 60;
    public long cooldownMin = 15;
    public long freezeHorizonMin = 180;
    public double capCriticalThreshold = 0.90;

    public enum Priority {
        URGENT,
        EXPRESS,
        STANDARD
    }

    public GAParams(int popSize, int generations, int tournamentK,
                    double pCrossover, double pMutation, double elitism) {
        this.popSize = popSize;
        this.generations = generations;
        this.tournamentK = tournamentK;
        this.pCrossover = pCrossover;
        this.pMutation = pMutation;
        this.elitism = elitism;
    }
}
