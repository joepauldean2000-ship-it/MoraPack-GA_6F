package com.morapack.ga;

import java.util.*;

public class GeneticOperators {
    private final Random rand = new Random();

    public GeneticOperators() {
    }

    // Selección por torneo (minimización)
    public Chromosome tournamentSelection(List<Chromosome> population) {
        Chromosome a = population.get(rand.nextInt(population.size()));
        Chromosome b = population.get(rand.nextInt(population.size()));
        return (a.fitness <= b.fitness) ? a : b;
    }

    // Crossover de un punto (con objetos Vuelo)
    public Chromosome crossover(Chromosome p1, Chromosome p2,
                                Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        int point = rand.nextInt(Math.max(1, p1.route.size()));
        List<Vuelo> childRoute = new ArrayList<>(p1.route.subList(0, Math.min(point, p1.route.size())));
        for (Vuelo v : p2.route) {
            if (!childRoute.contains(v)) {
                childRoute.add(v);
            }
        }
        Chromosome child = new Chromosome(childRoute);
        child.setPedido(pedido);
        child.fitness = Double.NaN;
        RoutesRepairer.repair(child);
        return child;
    }

    // Mutación: intercambiar vuelos
    public void mutate(Chromosome c, double mutationRate,
                       Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        if (rand.nextDouble() < mutationRate && c.route.size() > 1) {
            int i = rand.nextInt(c.route.size());
            int j = rand.nextInt(c.route.size());
            Collections.swap(c.route, i, j);
            RoutesRepairer.repair(c);
            c.fitness = Double.NaN;
        }
    }
}
