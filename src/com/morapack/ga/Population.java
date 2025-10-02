package com.morapack.ga;

import java.util.*;

public class Population {
    private final List<Chromosome> chromosomes;
    private final int populationSize;
    private final Random rand = new Random();

    public Population(int populationSize) {
        this.populationSize = populationSize;
        this.chromosomes = new ArrayList<>();
    }

    public void initialize(List<Vuelo> vuelosDisponibles, int routeLength,
                           Pedido pedido, Map<String, Aeropuerto> aeropuertos,
                           FlightCache cache) {
        chromosomes.clear();
        for (int i = 0; i < populationSize; i++) {
            Collections.shuffle(vuelosDisponibles, rand);
            List<Vuelo> route = new ArrayList<>(vuelosDisponibles.subList(0, Math.min(routeLength, vuelosDisponibles.size())));
            Chromosome c = Chromosome.ofRoute(cache, pedido, route);
            if (c != null) {
                RoutesRepairer.repair(c);
                chromosomes.add(c);
            }
        }
    }

    public List<Chromosome> getChromosomes() {
        return chromosomes;
    }

    public Chromosome getBestChromosome() {
        return chromosomes.stream().min(Comparator.comparingDouble(ch -> ch.fitness)).orElse(null);
    }

    // Selección por torneo
    public Chromosome tournamentSelection() {
        Chromosome a = chromosomes.get(rand.nextInt(chromosomes.size()));
        Chromosome b = chromosomes.get(rand.nextInt(chromosomes.size()));
        return (a.fitness < b.fitness) ? a : b;
    }

    // Crossover de un punto
    public Chromosome crossover(Chromosome p1, Chromosome p2, Pedido pedido,
                                Map<String, Aeropuerto> aeropuertos,
                                FlightCache cache) {
        List<Vuelo> p1Route = p1.getRoute();
        List<Vuelo> p2Route = p2.getRoute();
        if (p1Route.isEmpty()) {
            return Chromosome.ofRoute(cache, pedido, p2Route);
        }
        int point = rand.nextInt(Math.max(1, p1Route.size()));
        List<Vuelo> childRoute = new ArrayList<>(p1Route.subList(0, Math.min(point, p1Route.size())));
        for (Vuelo v : p2Route) {
            if (!childRoute.contains(v)) childRoute.add(v);
        }
        Chromosome child = Chromosome.ofRoute(cache, pedido, childRoute);
        if (child != null) {
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
        }
    }
}
