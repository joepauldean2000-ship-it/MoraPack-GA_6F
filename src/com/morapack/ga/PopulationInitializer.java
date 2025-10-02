package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Utility responsible for creating the initial GA population mixing greedy and random individuals.
 */
public final class PopulationInitializer {
    private PopulationInitializer() {
    }

    public static List<Chromosome> initPopulation(int size,
                                                  Heuristic heuristic,
                                                  List<Pedido> pedidos,
                                                  GraphVuelos graph,
                                                  PlanningState planningState,
                                                  FlightCache cache,
                                                  Random rnd) {
        if (size <= 0) {
            return Collections.emptyList();
        }
        List<Chromosome> population = new ArrayList<>(size);
        if (pedidos == null || pedidos.isEmpty() || graph == null || cache == null) {
            return population;
        }

        int greedyTarget = (int) Math.ceil(size * 0.3);
        int attempts = 0;
        int maxAttempts = Math.max(size * 10, 30);

        while (population.size() < greedyTarget && attempts < maxAttempts) {
            attempts++;
            if (heuristic == null) {
                break;
            }
            Pedido pedido = pedidos.get(rnd.nextInt(pedidos.size()));
            Chromosome greedy = heuristic.buildGreedy(pedido, graph, planningState, cache);
            if (greedy == null) {
                continue;
            }
            RoutesRepairer.repair(greedy);
            greedy.fitness = Double.NaN;
            if (greedy.isStructurallyFeasible()) {
                population.add(greedy);
            }
        }

        while (population.size() < size && attempts < maxAttempts) {
            attempts++;
            Chromosome randomChromosome = Chromosome.randomInit(pedidos, graph, planningState, cache, rnd);
            if (randomChromosome == null) {
                continue;
            }
            RoutesRepairer.repair(randomChromosome);
            randomChromosome.fitness = Double.NaN;
            if (randomChromosome.isStructurallyFeasible()) {
                population.add(randomChromosome);
            }
        }

        // If we still couldn't fill the population, attempt one more greedy pass as fallback.
        while (population.size() < size && heuristic != null && attempts < maxAttempts) {
            attempts++;
            Pedido pedido = pedidos.get(rnd.nextInt(pedidos.size()));
            Chromosome greedy = heuristic.buildGreedy(pedido, graph, planningState, cache);
            if (greedy == null) {
                continue;
            }
            RoutesRepairer.repair(greedy);
            greedy.fitness = Double.NaN;
            if (greedy.isStructurallyFeasible()) {
                population.add(greedy);
            }
        }

        return population;
    }
}

