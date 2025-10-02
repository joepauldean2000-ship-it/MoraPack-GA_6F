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
                           Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        chromosomes.clear();
        for (int i = 0; i < populationSize; i++) {
            Collections.shuffle(vuelosDisponibles, rand);
            List<Vuelo> route = new ArrayList<>(vuelosDisponibles.subList(0, Math.min(routeLength, vuelosDisponibles.size())));
            Chromosome c = new Chromosome(route);
            c.setPedido(pedido);
            RoutesRepairer.repair(c);
            chromosomes.add(c);
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
                                Map<String, Aeropuerto> aeropuertos) {
        int point = rand.nextInt(Math.max(1, p1.route.size()));
        List<Vuelo> childRoute = new ArrayList<>(p1.route.subList(0, Math.min(point, p1.route.size())));
        for (Vuelo v : p2.route) {
            if (!childRoute.contains(v)) childRoute.add(v);
        }
        Chromosome child = new Chromosome(childRoute);
        child.setPedido(pedido);
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
        }
    }
}
