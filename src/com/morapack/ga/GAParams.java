package com.morapack.ga;

public final class GAParams {
    public int popSize, generations, tournamentK;
    public double pCrossover, pMutation, elitism;

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
