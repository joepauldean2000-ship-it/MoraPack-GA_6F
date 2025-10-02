package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.*;

public class Population {
    private List<Chromosome> chromosomes;
    private int populationSize;
    private Random rand = new Random();
    private final PlanningState planningState;

    public Population(int populationSize, PlanningState planningState) {
        this.populationSize = populationSize;
        this.chromosomes = new ArrayList<>();
        this.planningState = planningState;
    }

    public void initialize(List<Vuelo> vuelosDisponibles, int routeLength,
                           Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        for (int i = 0; i < populationSize; i++) {
            Collections.shuffle(vuelosDisponibles, rand);
            List<Vuelo> route = new ArrayList<>(vuelosDisponibles.subList(0, Math.min(routeLength, vuelosDisponibles.size())));
            Chromosome c = new Chromosome(route);
            c.evaluate(pedido, aeropuertos, planningState);
            chromosomes.add(c);
        }
    }

    public List<Chromosome> getChromosomes() {
        return chromosomes;
    }

    public Chromosome getBestChromosome() {
        return Collections.max(chromosomes, Comparator.comparingDouble(c -> c.fitness));
    }

    // Selección por torneo
    public Chromosome tournamentSelection() {
        Chromosome a = chromosomes.get(rand.nextInt(chromosomes.size()));
        Chromosome b = chromosomes.get(rand.nextInt(chromosomes.size()));
        return (a.fitness > b.fitness) ? a : b;
    }

    // Crossover de un punto
    public Chromosome crossover(Chromosome p1, Chromosome p2, Pedido pedido,
                                Map<String, Aeropuerto> aeropuertos) {
        int point = rand.nextInt(p1.route.size());
        List<Vuelo> childRoute = new ArrayList<>(p1.route.subList(0, point));
        for (Vuelo v : p2.route) {
            if (!childRoute.contains(v)) childRoute.add(v);
        }
        Chromosome child = new Chromosome(childRoute);
        child.evaluate(pedido, aeropuertos, planningState);
        return child;
    }

    // Mutación: intercambiar vuelos
    public void mutate(Chromosome c, double mutationRate,
                       Pedido pedido, Map<String, Aeropuerto> aeropuertos) {
        if (rand.nextDouble() < mutationRate && c.route.size() > 1) {
            int i = rand.nextInt(c.route.size());
            int j = rand.nextInt(c.route.size());
            Collections.swap(c.route, i, j);
            c.evaluate(pedido, aeropuertos, planningState);
        }
    }
}
