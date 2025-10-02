package com.morapack.ga;

import com.morapack.ga.core.PlanningState;

/**
 * Heuristic builder used to seed the GA population with greedy routes.
 */
public interface Heuristic {
    Chromosome buildGreedy(Pedido pedido, GraphVuelos graph, PlanningState state);
}

