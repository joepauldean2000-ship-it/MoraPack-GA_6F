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
                                Pedido pedido, Map<String, Aeropuerto> aeropuertos,
                                FlightCache cache) {
        List<Vuelo> p1Route = p1.getRoute();
        List<Vuelo> p2Route = p2.getRoute();
        if (p1Route.isEmpty()) {
            return Chromosome.ofRoute(cache, pedido, p2Route);
        }
        int point = rand.nextInt(Math.max(1, p1Route.size()));
        List<Vuelo> childRoute = new ArrayList<>(p1Route.subList(0, Math.min(point, p1Route.size())));
        for (Vuelo v : p2Route) {
            if (!childRoute.contains(v)) {
                childRoute.add(v);
            }
        }
        Chromosome child = Chromosome.ofRoute(cache, pedido, childRoute);
        if (child != null) {
            child.fitness = Double.NaN;
            RoutesRepairer.repair(child);
        }
        return child;
    }

    // Mutación: intercambiar vuelos
    public void mutate(Chromosome c, double mutationRate,
                       Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        if (rand.nextDouble() < mutationRate && c.length() > 1) {
            int i = rand.nextInt(c.length());
            int j = rand.nextInt(c.length());
            c.swap(i, j);
            RoutesRepairer.repair(c);
            c.fitness = Double.NaN;
        }
    }
}
