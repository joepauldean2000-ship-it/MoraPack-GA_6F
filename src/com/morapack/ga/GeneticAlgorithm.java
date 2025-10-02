package com.morapack.ga;

import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.io.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

public class GeneticAlgorithm {
    int routeLength = 5;

    private final PlanningState planningState;
    private final SimClock clock;
    private final PlanLogger logger;
    private final Random random = new Random();

    public GeneticAlgorithm(PlanningState planningState, SimClock clock, PlanLogger logger) {
        this.planningState = planningState;
        this.clock = clock;
        this.logger = logger;
    }

    private static String flightKey(Vuelo v) {
        return String.valueOf(v.id);
    }

    public void run(List<Vuelo> vuelosDisponibles, List<Pedido> pedidos,
                    Map<String, Aeropuerto> aeropuertos, GAParams params,
                    GaMetricsCsv metricsCsv) {

        Map<String, Integer> capRest = planningState.flightCapacity();
        capRest.clear();
        for (Vuelo v : vuelosDisponibles) {
            capRest.put(flightKey(v), v.capacidad);
        }

        int totalSolicitados = 0, totalAsignados = 0, totalPendientes = 0;

        try (PrintWriter writer = new PrintWriter("plan_asignacion_GA.csv")) {
            writer.println("pedido_id,dia,hub_origen,destino,ruta,asignados,pendientes,fitness");

            logger.logMetric("startup", 1.0, clock.now());

            for (Pedido p : pedidos) {
                totalSolicitados += p.cantidad;
                int restantes = p.cantidad;
                int asignadosTotal = 0;

                while (restantes > 0) {
                    Population pop = new Population(params.popSize, planningState);
                    pop.initialize(vuelosDisponibles, routeLength, p, aeropuertos);

                    List<Chromosome> initialPopulation = new ArrayList<>();
                    for (Chromosome c : pop.getChromosomes()) {
                        initialPopulation.add(copyOf(c));
                    }

                    ToDoubleFunction<Chromosome> fitnessFn = chromosome -> {
                        chromosome.evaluate(p, aeropuertos, planningState);
                        return chromosome.fitness;
                    };

                    List<Chromosome> finalPopulation = evolve(initialPopulation, params, fitnessFn, logger, clock, metricsCsv);

                    Chromosome best = finalPopulation.stream()
                            .max(Comparator.comparingDouble(c -> c.fitness))
                            .orElse(null);

                    if (best == null) {
                        break;
                    }

                    // cuello de botella
                    int cuello = Integer.MAX_VALUE;
                    for (Vuelo v : best.route) {
                        cuello = Math.min(cuello, capRest.getOrDefault(flightKey(v), v.capacidad));
                    }
                    if (cuello <= 0) break;

                    int asignados = Math.min(restantes, cuello);
                    restantes -= asignados;
                    asignadosTotal += asignados;

                    // actualizar capacidades de vuelos
                    for (Vuelo v : best.route) {
                        String key = flightKey(v);
                        capRest.put(key, capRest.get(key) - asignados);
                    }

// ✅ nuevo: ocupar almacén destino por 2h
                    Aeropuerto apDest = aeropuertos.get(p.destino);
                    if (apDest != null) {
                        // calcular minuto de llegada absoluta
                        int minutoLlegada = p.dia * 24 * 60 + p.hora * 60 + p.minuto;
                        for (Vuelo v : best.route) {
                            minutoLlegada += (int)(v.horasDuracion * 60);
                        }
                        // ocupar 2h = 120 minutos
                        clock.advanceTo(minutoLlegada);
                        java.util.NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(p.destino);
                        for (long m = minutoLlegada; m < minutoLlegada + 120; m++) {
                            timeline.put(m, timeline.getOrDefault(m, 0) + asignados);
                        }
                    }


                    writer.printf("%s,%d,%s,%s,\"%s\",%d,%d,%.2f%n",
                            p.id, p.dia, p.hubOrigen, p.destino,
                            best.route.toString(), asignados, restantes, best.fitness);

                    logger.logMetric("pedido_asignado", asignados, clock.now());

                    if (restantes == 0) break;
                }

                totalAsignados += asignadosTotal;
                totalPendientes += restantes;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Resumen en consola
        System.out.println("\n=== Resumen GA ===");
        System.out.println("Pedidos totales: " + pedidos.size());
        System.out.println("Paquetes solicitados: " + totalSolicitados);
        System.out.println("Paquetes asignados: " + totalAsignados);
        System.out.println("Paquetes pendientes: " + totalPendientes);
    }

    public Chromosome tournamentSelect(List<Chromosome> pop, int k,
                                       ToDoubleFunction<Chromosome> fitnessFn) {
        if (pop == null || pop.isEmpty()) {
            return null;
        }
        int sampleSize = Math.max(1, Math.min(k, pop.size()));
        Set<Integer> picked = new HashSet<>();
        Chromosome best = null;
        double bestFitness = Double.NEGATIVE_INFINITY;
        while (picked.size() < sampleSize) {
            int idx = random.nextInt(pop.size());
            if (!picked.add(idx)) {
                continue;
            }
            Chromosome candidate = pop.get(idx);
            double fit = Double.isNaN(candidate.fitness) ? fitnessFn.applyAsDouble(candidate) : candidate.fitness;
            if (best == null || fit > bestFitness) {
                best = candidate;
                bestFitness = fit;
            }
        }
        return best;
    }

    public Chromosome crossoverOnePoint(Chromosome a, Chromosome b) {
        if (a == null) {
            return null;
        }
        if (b == null || a.route.size() < 2 || b.route.isEmpty()) {
            return copyOf(a);
        }
        int maxCut = Math.min(a.route.size(), Math.max(1, b.route.size()));
        if (maxCut < 2) {
            return copyOf(a);
        }
        int cut = 1 + random.nextInt(maxCut - 1);
        List<Vuelo> childRoute = new ArrayList<>(a.route.subList(0, Math.min(cut, a.route.size())));
        for (Vuelo vuelo : b.route) {
            if (!childRoute.contains(vuelo)) {
                childRoute.add(vuelo);
            }
        }
        Chromosome child = new Chromosome(childRoute);
        child.fitness = Double.NaN;
        return child;
    }

    public void mutateSwap(Chromosome c, double p) {
        if (c == null || c.route.size() < 2) {
            return;
        }
        if (random.nextDouble() < p) {
            int i = random.nextInt(c.route.size());
            int j = random.nextInt(c.route.size());
            while (j == i) {
                j = random.nextInt(c.route.size());
            }
            Collections.swap(c.route, i, j);
            c.fitness = Double.NaN;
        }
    }

    public List<Chromosome> evolve(List<Chromosome> initial, GAParams params,
                                   ToDoubleFunction<Chromosome> fitnessFn,
                                   PlanLogger logger, SimClock clock,
                                   GaMetricsCsv metricsCsv) {
        List<Chromosome> population = new ArrayList<>();
        if (initial != null) {
            for (Chromosome c : initial) {
                population.add(copyOf(c));
            }
        }
        if (population.isEmpty()) {
            return population;
        }

        int popSize = params.popSize;
        int eliteCount = params.elitism > 0 ? (int) Math.ceil(params.elitism * popSize) : 0;
        eliteCount = Math.min(eliteCount, popSize);

        for (int generation = 0; generation < params.generations; generation++) {
            PopulationStats stats = evaluatePopulation(population, fitnessFn);
            logger.logMetric("ga.best", stats.best, clock.now());
            logger.logMetric("ga.avg", stats.avg, clock.now());
            logger.logMetric("ga.worst", stats.worst, clock.now());
            if (!Double.isNaN(stats.feasibleRatio)) {
                logger.logMetric("ga.feasible", stats.feasibleRatio, clock.now());
            }
            if (metricsCsv != null) {
                metricsCsv.logGaStats(new GaStats(generation + 1, stats.best, stats.avg, stats.worst, stats.feasibleRatio));
            }

            List<Chromosome> next = new ArrayList<>(popSize);
            if (eliteCount > 0) {
                List<Chromosome> sorted = new ArrayList<>(population);
                sorted.sort(Comparator.comparingDouble((Chromosome c) -> c.fitness).reversed());
                for (int i = 0; i < eliteCount && next.size() < popSize && i < sorted.size(); i++) {
                    next.add(copyOf(sorted.get(i)));
                }
            }

            while (next.size() < popSize) {
                Chromosome parent1 = tournamentSelect(population, params.tournamentK, fitnessFn);
                Chromosome parent2 = tournamentSelect(population, params.tournamentK, fitnessFn);
                if (parent1 == null) {
                    break;
                }
                Chromosome child;
                if (parent2 == null) {
                    child = copyOf(parent1);
                } else if (random.nextDouble() < params.pCrossover) {
                    child = crossoverOnePoint(parent1, parent2);
                } else {
                    child = copyOf(parent1);
                }
                mutateSwap(child, params.pMutation);
                if (child != null) {
                    next.add(child);
                }
            }

            if (!next.isEmpty()) {
                if (next.size() > popSize) {
                    next = new ArrayList<>(next.subList(0, popSize));
                }
                population = next;
            }
        }

        evaluatePopulation(population, fitnessFn);
        return population;
    }

    private Chromosome copyOf(Chromosome c) {
        if (c == null) {
            return null;
        }
        Chromosome copy = new Chromosome(c.route);
        copy.fitness = c.fitness;
        return copy;
    }

    private PopulationStats evaluatePopulation(List<Chromosome> population,
                                               ToDoubleFunction<Chromosome> fitnessFn) {
        PopulationStats stats = new PopulationStats();
        if (population.isEmpty()) {
            stats.best = Double.NaN;
            stats.avg = Double.NaN;
            stats.worst = Double.NaN;
            stats.feasibleRatio = Double.NaN;
            return stats;
        }

        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        double sum = 0.0;
        int feasible = 0;
        for (Chromosome chromosome : population) {
            double fitness = fitnessFn.applyAsDouble(chromosome);
            chromosome.fitness = fitness;
            best = Math.max(best, fitness);
            worst = Math.min(worst, fitness);
            sum += fitness;
            // Convención actual: fitness positivo indica soluciones factibles; penalizaciones dejan fitness <= 0.
            if (fitness > 0) {
                feasible++;
            }
        }
        stats.best = best;
        stats.worst = worst;
        stats.avg = sum / population.size();
        stats.feasibleRatio = population.isEmpty() ? Double.NaN : ((double) feasible) / population.size();
        return stats;
    }

    private static final class PopulationStats {
        double best;
        double avg;
        double worst;
        double feasibleRatio;
    }
}
