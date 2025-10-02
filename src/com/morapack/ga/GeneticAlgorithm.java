package com.morapack.ga;

import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import com.morapack.io.BestSolutionLogger;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

public class GeneticAlgorithm {
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final double LARGE_PENALTY = 1_000_000.0;
    private static final double MIN_MUTATION = 0.04;
    private static final double MAX_MUTATION = 0.15;
    private static volatile double COST_WEIGHT = 0.002;   // Prioridad 1: exponer en configuración
    private static volatile double TIME_WEIGHT = 0.0015;  // Prioridad 1: evitar dominancia
    private static volatile double DISTANCE_WEIGHT = 0.0008; // Prioridad 1: γ << β por defecto

    private static final boolean SINGLE_FILE_BEST_EXPORT =
            Boolean.parseBoolean(System.getProperty("morapack.bestCsv.single", "true"));

    private final PlanningState planningState;
    private final SimClock clock;
    private final PlanLogger logger;
    private final Random random = new Random();
    private long rngSeed;
    private FlightCache cache;
    private GraphVuelos graph;
    private Heuristic greedyHeuristic;
    private Map<String, Aeropuerto> aeropuertos;
    private BestSolutionLogger bestSolutionLogger;
    private List<Vuelo> vuelosDisponibles;
    private GaMetricsCsv metricsCsv;
    private BusinessRules currentRules;
    private GAParams currentParams;
    private PrintWriter assignmentWriter;
    private boolean assignmentHeaderWritten;
    private int totalSolicitados;
    private int totalAsignados;
    private int totalPendientes;
    private int pedidosProcesados;
    private final Map<String, Integer> baseCapacities = new HashMap<>();

    public GeneticAlgorithm(PlanningState planningState, SimClock clock, PlanLogger logger) {
        this.planningState = planningState;
        this.clock = clock;
        this.logger = logger;
        this.rngSeed = System.currentTimeMillis();
        this.random.setSeed(rngSeed);
    }

    private static String flightKey(Vuelo v) {
        return String.valueOf(v.id);
    }

    public void run(List<Vuelo> vuelosDisponibles, List<Pedido> pedidos,
                    Map<String, Aeropuerto> aeropuertos, GAParams params,
                    GaMetricsCsv metricsCsv, BusinessRules businessRules) {
        try (PrintWriter writer = new PrintWriter("plan_asignacion_GA.csv")) {
            setupExecution(vuelosDisponibles, aeropuertos, params, businessRules, metricsCsv, writer);
            if (pedidos != null && !pedidos.isEmpty()) {
                runGAForBatch(new ArrayList<>(pedidos), planningState, businessRules, params);
            }
            finalizeExecution();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setupExecution(List<Vuelo> vuelosDisponibles,
                               Map<String, Aeropuerto> aeropuertos,
                               GAParams params,
                               BusinessRules businessRules,
                               GaMetricsCsv metricsCsv,
                               PrintWriter assignmentWriter) {
        this.vuelosDisponibles = vuelosDisponibles != null ? new ArrayList<>(vuelosDisponibles) : Collections.emptyList();
        this.aeropuertos = aeropuertos;
        this.metricsCsv = metricsCsv;
        this.currentRules = businessRules;
        this.currentParams = params;
        syncFitnessWeights(params);
        this.assignmentWriter = assignmentWriter;
        this.assignmentHeaderWritten = false;
        this.totalSolicitados = 0;
        this.totalAsignados = 0;
        this.totalPendientes = 0;
        this.pedidosProcesados = 0;

        rngSeed = System.currentTimeMillis();
        random.setSeed(rngSeed);
        logger.logMetric("ga.seed", rngSeed, clock.now());
        System.out.println("GA RNG seed: " + rngSeed);

        Map<String, Integer> capRest = planningState.flightCapacity();
        capRest.clear();
        baseCapacities.clear();
        for (Vuelo vuelo : this.vuelosDisponibles) {
            String key = flightKey(vuelo);
            capRest.put(key, vuelo.capacidad);
            baseCapacities.put(key, vuelo.capacidad);
        }

        this.cache = new FlightCache(this.vuelosDisponibles);
        this.graph = new GraphVuelos(this.vuelosDisponibles);
        this.greedyHeuristic = buildDefaultHeuristic();

        logger.logMetric("startup", 1.0, clock.now());

        if (this.assignmentWriter != null && !assignmentHeaderWritten) {
            this.assignmentWriter.println("pedido_id,dia,hub_origen,destino,ruta,asignados,pendientes,fitness");
            this.assignmentWriter.flush();
            this.assignmentHeaderWritten = true;
        }

        if (SINGLE_FILE_BEST_EXPORT) {
            if (bestSolutionLogger == null) {
                bestSolutionLogger = new BestSolutionLogger("out/best_solutions.csv");
            }
            try {
                bestSolutionLogger.open();
            } catch (IOException e) {
                System.err.println("Failed to open best solution logger: " + e.getMessage());
                bestSolutionLogger = null;
            }
        } else if (bestSolutionLogger != null) {
            bestSolutionLogger.close();
            bestSolutionLogger = null;
        }
    }

    private Heuristic buildDefaultHeuristic() {
        return (pedido, g, st, c) -> {
            if (pedido == null || g == null || c == null) {
                return null;
            }
            String origin = pedido.hubOrigen;
            String destination = pedido.destino;
            if (origin == null || destination == null || origin.equals(destination)) {
                return null;
            }
            List<Vuelo> path = g.shortestPathByDuration(origin, destination, st);
            if (path.isEmpty()) {
                return null;
            }
            Chromosome chromosome = Chromosome.ofRoute(c, pedido, path);
            if (chromosome != null) {
                chromosome.fitness = Double.NaN;
            }
            return chromosome;
        };
    }

    private static void syncFitnessWeights(GAParams params) {
        if (params == null) {
            return;
        }
        if (params.weightCost >= 0.0) {
            COST_WEIGHT = params.weightCost;
        }
        if (params.weightTime >= 0.0) {
            TIME_WEIGHT = params.weightTime;
        }
        if (params.weightDistance >= 0.0) {
            DISTANCE_WEIGHT = params.weightDistance;
        }
    }

    public void runGAForBatch(List<Pedido> batch,
                              PlanningState state,
                              BusinessRules businessRules,
                              GAParams params) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (state != null && state != this.planningState) {
            throw new IllegalArgumentException("PlanningState mismatch for GA execution");
        }
        this.currentRules = businessRules != null ? businessRules : this.currentRules;
        this.currentParams = params != null ? params : this.currentParams;
        if (this.cache == null || this.graph == null) {
            throw new IllegalStateException("GA environment not prepared. Call setupExecution first.");
        }
        syncFitnessWeights(this.currentParams);

        Map<String, Integer> capRest = planningState.flightCapacity();

        for (Pedido pedido : batch) {
            if (pedido == null || pedido.isCancelled()) {
                continue;
            }
            if (pedido.markProcessedOnce()) {
                pedidosProcesados++;
                totalSolicitados += pedido.cantidad;
            }
            int restantes = pedido.getRemainingQuantity();
            if (restantes <= 0) {
                pedido.setAtRisk(false);
                continue;
            }
            int asignadosTotal = 0;
            boolean assignedThisRun = false;

            while (restantes > 0) {
                List<Chromosome> initialPopulation = PopulationInitializer.initForBatch(
                        Collections.singletonList(pedido),
                        planningState,
                        currentRules,
                        currentParams,
                        graph,
                        cache,
                        greedyHeuristic,
                        random);

                if (initialPopulation.isEmpty()) {
                    break;
                }

                EvolutionOutcome outcome = evolve(initialPopulation, currentParams, currentRules, logger, clock,
                        metricsCsv, cache, greedyHeuristic, Collections.singletonList(pedido), graph, pedido);
                Chromosome best = outcome.best;

                if (best == null || best.getRoute().isEmpty() || !Double.isFinite(best.fitness)) {
                    break;
                }

                int cuello = Integer.MAX_VALUE;
                for (Vuelo vuelo : best.getRoute()) {
                    cuello = Math.min(cuello, capRest.getOrDefault(flightKey(vuelo), vuelo.capacidad));
                }
                if (cuello <= 0) {
                    break;
                }

                Aeropuerto destino = aeropuertos != null ? aeropuertos.get(pedido.destino) : null;
                int warehouseLimit = computeWarehouseLimit(best, destino, currentRules);
                if (warehouseLimit <= 0) {
                    break;
                }

                int asignados = Math.min(restantes, Math.min(cuello, warehouseLimit));
                if (asignados <= 0) {
                    break;
                }

                asignadosTotal += asignados;
                assignedThisRun = true;

                for (Vuelo vuelo : best.getRoute()) {
                    String key = flightKey(vuelo);
                    capRest.put(key, capRest.get(key) - asignados);
                }

                int arrivalMinute = best.getArrivalMinute();
                int departureMinute = best.getDepartureMinute();
                if (arrivalMinute >= 0 && destino != null) {
                    clock.advanceTo(arrivalMinute);
                    NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(pedido.destino);
                    for (long minute = arrivalMinute; minute < arrivalMinute + currentRules.whBlockMin; minute++) {
                        timeline.put(minute, timeline.getOrDefault(minute, 0) + asignados);
                    }
                }

                pedido.addAssignment(best.getRoute(), departureMinute, arrivalMinute, asignados);
                restantes = pedido.getRemainingQuantity();

                if (assignmentWriter != null) {
                    assignmentWriter.printf("%s,%d,%s,%s,\"%s\",%d,%d,%.4f%n",
                            pedido.id, pedido.dia, pedido.hubOrigen, pedido.destino,
                            best.getRoute().toString(), asignados, pedido.getRemainingQuantity(), best.fitness);
                }

                logger.logMetric("pedido_asignado", asignados, clock.now());

                if (restantes == 0) {
                    break;
                }
            }

            totalAsignados += asignadosTotal;
            totalPendientes += pedido.getRemainingQuantity();
            if (pedido.isUrgent() && pedido.getRemainingQuantity() > 0) {
                pedido.setAtRisk(true);
            } else if (assignedThisRun && pedido.getRemainingQuantity() <= 0) {
                pedido.setAtRisk(false);
            }
        }

        if (assignmentWriter != null) {
            assignmentWriter.flush();
        }
    }

    public void finalizeExecution() {
        if (assignmentWriter != null) {
            assignmentWriter.flush();
        }
        if (bestSolutionLogger != null) {
            bestSolutionLogger.close();
            bestSolutionLogger = null;
        }
        System.out.println("\n=== Resumen GA ===");
        System.out.println("Pedidos totales: " + pedidosProcesados);
        System.out.println("Paquetes solicitados: " + totalSolicitados);
        System.out.println("Paquetes asignados: " + totalAsignados);
        System.out.println("Paquetes pendientes: " + totalPendientes);
    }

    public EvolutionOutcome evolve(List<Chromosome> initial,
                                   GAParams params,
                                   BusinessRules businessRules,
                                   PlanLogger logger,
                                   SimClock clock,
                                   GaMetricsCsv metricsCsv,
                                   FlightCache cache,
                                   Heuristic heuristic,
                                   List<Pedido> pedidoPool,
                                   GraphVuelos graph,
                                   Pedido pedidoContext) {
        if (initial == null || initial.isEmpty()) {
            return new EvolutionOutcome(Collections.emptyList(), null, Double.NaN, 0);
        }

        List<Chromosome> population = new ArrayList<>(initial.size());
        for (Chromosome c : initial) {
            population.add(copyOf(c));
        }

        int popSize = params.popSize;
        if (popSize <= 0) {
            popSize = population.size();
        }
        double mutationRate = Math.max(MIN_MUTATION, params.pMutation);
        double crossoverRate = params.pCrossover;
        int eliteCount = params.elitism > 0
                ? Math.max(1, (int) Math.ceil(Math.min(params.elitism, 0.02) * popSize))
                : 0;
        eliteCount = Math.min(eliteCount, popSize);

        ToDoubleFunction<Chromosome> fitnessFn = c -> fitness(c, businessRules, planningState, cache);

        Chromosome globalBest = null;
        double globalBestFitness = Double.POSITIVE_INFINITY;
        int globalBestGeneration = 0;
        int generationsWithoutImprovement = 0;
        int stagnantCounter = 0;

        PopulationStats initialStats = evaluatePopulation(population, fitnessFn);
        globalBest = copyOf(initialStats.bestChromosome);
        globalBestFitness = initialStats.best;

        for (int generation = 1; generation <= params.generations; generation++) {
            PopulationStats stats = evaluatePopulation(population, fitnessFn);
            double diversity = computeDiversityIndex(population);

            logger.logMetric("ga.best", stats.best, clock.now());
            logger.logMetric("ga.avg", stats.avg, clock.now());
            logger.logMetric("ga.worst", stats.worst, clock.now());
            if (!Double.isNaN(stats.feasibleRatio)) {
                logger.logMetric("ga.feasible", stats.feasibleRatio, clock.now());
            }
            logger.logMetric("ga.mutationRate", mutationRate, clock.now());
            logger.logMetric("ga.diversity", diversity, clock.now());

            if (metricsCsv != null) {
                metricsCsv.logGaStats(new GaStats(generation, stats.best, stats.avg, stats.worst,
                        stats.feasibleRatio, mutationRate, diversity,
                        stats.p95Transit, stats.p95Wait, stats.p95Cost, stats.maxHopsRef));
            }

            if (stats.best < globalBestFitness - 1e-6) {
                globalBestFitness = stats.best;
                globalBest = copyOf(stats.bestChromosome);
                globalBestGeneration = generation;
                generationsWithoutImprovement = 0;
                stagnantCounter = 0;
                mutationRate = Math.max(MIN_MUTATION, mutationRate * 0.7);
                writeBestSolution(globalBest, generation, pedidoContext);
            } else {
                generationsWithoutImprovement++;
                stagnantCounter = Math.abs(stats.avg - stats.best) < 1e-3 ? stagnantCounter + 1 : 0;
                if (generationsWithoutImprovement % 10 == 0) {
                    mutationRate = Math.min(MAX_MUTATION, mutationRate * 1.5);
                    injectImmigrants(population, (int) Math.max(1, Math.round(popSize * 0.07)),
                            heuristic, pedidoPool, graph, cache);
                }
            }

            if (stagnantCounter >= 20) {
                reinitializeWorst(population, popSize / 2, heuristic, pedidoPool, graph, cache);
                stagnantCounter = 0;
            }

            if (generationsWithoutImprovement >= 25) {
                break;
            }

            List<Chromosome> next = new ArrayList<>(popSize);
            if (eliteCount > 0) {
                List<Chromosome> sorted = new ArrayList<>(population);
                sorted.sort(Comparator.comparingDouble(c -> c.fitness));
                for (int i = 0; i < eliteCount && i < sorted.size(); i++) {
                    Chromosome elite = copyOf(sorted.get(i));
                    RoutesRepairer.repair(elite);
                    next.add(elite);
                }
            }

            while (next.size() < popSize) {
                Chromosome parent1 = tournamentSelect(population, Math.max(2, params.tournamentK), fitnessFn);
                Chromosome parent2 = tournamentSelect(population, Math.max(2, params.tournamentK), fitnessFn);
                if (parent1 == null) {
                    break;
                }
                Chromosome child;
                if (parent2 == null || random.nextDouble() >= crossoverRate) {
                    child = copyOf(parent1);
                } else {
                    child = crossoverOrder(parent1, parent2);
                }
                applyMutations(child, mutationRate);
                if (child != null) {
                    RoutesRepairer.repair(child);
                    child.fitness = Double.NaN;
                    child.markDirty(0, child.length() - 1);
                    next.add(child);
                }
            }

            if (next.size() > popSize) {
                next = new ArrayList<>(next.subList(0, popSize));
            }
            population = next;
        }

        evaluatePopulation(population, fitnessFn);
        return new EvolutionOutcome(population, globalBest, globalBestFitness, globalBestGeneration);
    }

    private Chromosome crossoverOrder(Chromosome parent1, Chromosome parent2) {
        if (parent1 == null) {
            return null;
        }
        int len1 = parent1.length();
        int len2 = parent2 != null ? parent2.length() : 0;
        if (len1 < 2 || len2 < 2) {
            return copyOf(parent1);
        }
        int length = Math.min(len1, len2);
        int[] genes1 = Arrays.copyOf(parent1.genes(), length);
        int[] genes2 = Arrays.copyOf(parent2.genes(), length);
        int[] childGenes = new int[length];
        Arrays.fill(childGenes, -1);
        int cut1 = random.nextInt(length);
        int cut2 = random.nextInt(length);
        if (cut1 > cut2) {
            int tmp = cut1;
            cut1 = cut2;
            cut2 = tmp;
        }
        Set<Integer> used = new HashSet<>();
        for (int i = cut1; i <= cut2; i++) {
            childGenes[i] = genes1[i];
            used.add(genes1[i]);
        }
        int fillIndex = (cut2 + 1) % length;
        for (int i = 0; i < length; i++) {
            int gene = genes2[(cut2 + 1 + i) % length];
            if (used.contains(gene)) {
                continue;
            }
            childGenes[fillIndex] = gene;
            used.add(gene);
            fillIndex = (fillIndex + 1) % length;
        }
        for (int i = 0; i < length; i++) {
            if (childGenes[i] == -1) {
                childGenes[i] = genes1[i];
            }
        }
        Chromosome child = new Chromosome(parent1.getCache(), childGenes);
        child.setPedido(parent1.getPedido());
        child.fitness = Double.NaN;
        child.markDirty(0, child.length() - 1);
        return child;
    }

    private void applyMutations(Chromosome chromosome, double mutationRate) {
        if (chromosome == null || chromosome.length() < 2) {
            return;
        }
        if (random.nextDouble() < mutationRate) {
            mutateSwap(chromosome);
        }
        if (chromosome.length() >= 3 && random.nextDouble() < mutationRate) {
            mutateTwoOpt(chromosome);
        }
        if (chromosome.length() >= 4 && random.nextDouble() < mutationRate / 2.0) {
            mutateThreeOpt(chromosome);
        }
        if (chromosome.length() >= 4 && random.nextDouble() < mutationRate) {
            mutateSegmentShuffle(chromosome);
        }
        chromosome.fitness = Double.NaN;
    }

    private void mutateSwap(Chromosome chromosome) {
        int len = chromosome.length();
        if (len < 2) {
            return;
        }
        int i = random.nextInt(len);
        int j = random.nextInt(len);
        while (j == i) {
            j = random.nextInt(len);
        }
        chromosome.swap(i, j);
    }

    private void mutateTwoOpt(Chromosome chromosome) {
        int len = chromosome.length();
        if (len < 3) {
            return;
        }
        int i = random.nextInt(len - 1);
        int j = i + 1 + random.nextInt(len - i - 1);
        chromosome.reverse(i, j);
    }

    private void mutateThreeOpt(Chromosome chromosome) {
        int len = chromosome.length();
        if (len < 4) {
            return;
        }
        int a = random.nextInt(len);
        int b = random.nextInt(len);
        int c = random.nextInt(len);
        while (b == a) {
            b = random.nextInt(len);
        }
        while (c == a || c == b) {
            c = random.nextInt(len);
        }
        chromosome.rotateThree(a, b, c);
    }

    private void mutateSegmentShuffle(Chromosome chromosome) {
        int len = chromosome.length();
        if (len < 4) {
            return;
        }
        int start = random.nextInt(len - 1);
        int end = start + 1 + random.nextInt(len - start - 1);
        chromosome.shuffleSegment(start, end, random);
    }

    private Chromosome tournamentSelect(List<Chromosome> pop, int k,
                                        ToDoubleFunction<Chromosome> fitnessFn) {
        if (pop == null || pop.isEmpty()) {
            return null;
        }
        int sampleSize = Math.max(1, Math.min(k, pop.size()));
        Chromosome best = null;
        double bestFitness = Double.POSITIVE_INFINITY;
        Set<Integer> picked = new HashSet<>();
        while (picked.size() < sampleSize) {
            int idx = random.nextInt(pop.size());
            if (!picked.add(idx)) {
                continue;
            }
            Chromosome candidate = pop.get(idx);
            double fit = Double.isNaN(candidate.fitness) ? fitnessFn.applyAsDouble(candidate) : candidate.fitness;
            if (best == null || fit < bestFitness) {
                best = candidate;
                bestFitness = fit;
            }
        }
        return best;
    }

    private void injectImmigrants(List<Chromosome> population, int count,
                                  Heuristic heuristic, List<Pedido> pedidos,
                                  GraphVuelos graph, FlightCache cache) {
        if (population.isEmpty() || count <= 0) {
            return;
        }
        List<Chromosome> immigrants = PopulationInitializer.initPopulation(count, heuristic, pedidos, graph,
                planningState, cache, random);
        if (immigrants.isEmpty()) {
            return;
        }
        population.sort(Comparator.comparingDouble(c -> c.fitness));
        int size = population.size();
        for (int i = 0; i < count && i < immigrants.size(); i++) {
            Chromosome immigrant = immigrants.get(i);
            immigrant.fitness = Double.NaN;
            population.set(size - 1 - i, immigrant);
        }
    }

    private void reinitializeWorst(List<Chromosome> population, int count,
                                   Heuristic heuristic, List<Pedido> pedidos,
                                   GraphVuelos graph, FlightCache cache) {
        if (population.isEmpty() || count <= 0) {
            return;
        }
        population.sort(Comparator.comparingDouble(c -> c.fitness));
        List<Chromosome> replacements = PopulationInitializer.initPopulation(count, heuristic, pedidos, graph,
                planningState, cache, random);
        for (int i = 0; i < count && i < replacements.size(); i++) {
            Chromosome repl = replacements.get(i);
            repl.fitness = Double.NaN;
            population.set(population.size() - 1 - i, repl);
        }
    }

    private PopulationStats evaluatePopulation(List<Chromosome> population,
                                               ToDoubleFunction<Chromosome> fitnessFn) {
        PopulationStats stats = new PopulationStats();
        if (population.isEmpty()) {
            stats.best = Double.NaN;
            stats.avg = Double.NaN;
            stats.worst = Double.NaN;
            stats.feasibleRatio = Double.NaN;
            stats.bestChromosome = null;
            return stats;
        }

        double best = Double.POSITIVE_INFINITY;
        double worst = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        int feasible = 0;
        Chromosome bestChrom = null;
        FlightCache localCache = null;
        for (Chromosome chromosome : population) {
            double fitness = fitnessFn.applyAsDouble(chromosome);
            chromosome.fitness = fitness;
            if (fitness < best) {
                best = fitness;
                bestChrom = chromosome;
            }
            if (fitness > worst) {
                worst = fitness;
            }
            sum += fitness;
            if (RoutesRepairer.isFeasible(chromosome, planningState) && Double.isFinite(fitness)) {
                feasible++;
            }
            if (localCache == null && chromosome != null) {
                localCache = chromosome.getCache();
            }
        }
        stats.best = best;
        stats.worst = worst;
        stats.avg = sum / population.size();
        stats.feasibleRatio = population.isEmpty() ? Double.NaN : ((double) feasible) / population.size();
        stats.bestChromosome = bestChrom != null ? bestChrom : population.get(0);
        if (localCache != null) {
            stats.p95Transit = localCache.p95TransitMinutes();
            stats.p95Wait = localCache.p95WaitMinutes();
            stats.p95Cost = localCache.p95RouteCost();
            stats.maxHopsRef = localCache.maxHopsRef();
        }
        return stats;
    }

    private double computeDiversityIndex(List<Chromosome> population) {
        if (population.size() < 2) {
            return 0.0;
        }
        int maxLen = 0;
        for (Chromosome c : population) {
            maxLen = Math.max(maxLen, c.length());
        }
        if (maxLen == 0) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (int i = 0; i < population.size(); i++) {
            for (int j = i + 1; j < population.size(); j++) {
                Chromosome a = population.get(i);
                Chromosome b = population.get(j);
                double diff = 0.0;
                for (int pos = 0; pos < maxLen; pos++) {
                    int geneA = pos < a.length() ? a.geneAt(pos) : -1;
                    int geneB = pos < b.length() ? b.geneAt(pos) : -1;
                    if (geneA != geneB) {
                        diff++;
                    }
                }
                diff /= maxLen;
                total += diff;
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : total / pairs;
    }

    private Chromosome copyOf(Chromosome chromosome) {
        if (chromosome == null) {
            return null;
        }
        Chromosome copy = new Chromosome(chromosome.getCache(), Arrays.copyOf(chromosome.genes(), chromosome.length()));
        copy.fitness = chromosome.fitness;
        copy.setStructurallyFeasible(chromosome.isStructurallyFeasible());
        copy.setPedido(chromosome.getPedido());
        copy.setScheduleWindow(chromosome.getDepartureMinute(), chromosome.getArrivalMinute());
        copy.setPunctualityMinutes(chromosome.getPunctualityMinutes());
        copy.setSlaDelayMinutes(chromosome.getSlaDelayMinutes());
        copy.setTotalCost(chromosome.getTotalCost());
        copy.setWaitingMinutes(chromosome.getWaitingMinutes());
        copy.setCapacityUsage(chromosome.getCapacityUsage());
        copy.setStopovers(chromosome.getStopovers());
        copy.setTotalDistanceKm(chromosome.getTotalDistanceKm());
        double[] srcDur = chromosome.segmentDuration();
        double[] srcWait = chromosome.segmentWaiting();
        double[] srcCost = chromosome.segmentCost();
        double[] srcLoad = chromosome.segmentLoad();
        double[] dstDur = copy.segmentDuration();
        double[] dstWait = copy.segmentWaiting();
        double[] dstCost = copy.segmentCost();
        double[] dstLoad = copy.segmentLoad();
        System.arraycopy(srcDur, 0, dstDur, 0, Math.min(srcDur.length, dstDur.length));
        System.arraycopy(srcWait, 0, dstWait, 0, Math.min(srcWait.length, dstWait.length));
        System.arraycopy(srcCost, 0, dstCost, 0, Math.min(srcCost.length, dstCost.length));
        System.arraycopy(srcLoad, 0, dstLoad, 0, Math.min(srcLoad.length, dstLoad.length));
        if (Double.isFinite(copy.fitness)) {
            copy.clearDirty();
        }
        return copy;
    }

    private void writeBestSolution(Chromosome best, int generation, Pedido pedidoContext) {
        if (best == null) {
            return;
        }
        String pedidoId = (pedidoContext != null && pedidoContext.id != null) ? pedidoContext.id : "NA";
        if (SINGLE_FILE_BEST_EXPORT && bestSolutionLogger != null) {
            double fitnessValue = best.fitness;
            double totalCost = best.getTotalCost();
            double totalTransit = best.getPunctualityMinutes();
            double slaDelay = best.getSlaDelayMinutes();
            double avgLoad = best.getCapacityUsage();
            int hops = best.length();
            double stopovers = hops > 0 ? Math.max(0, hops - 1) : 0.0;
            best.setStopovers(stopovers);
            double distKm = best.getTotalDistanceKm();
            if (distKm <= 0.0) {
                distKm = best.computeTotalDistanceKm();
            }
            int violSla = slaDelay > 0.0 ? 1 : 0;
            int violCap = 0; // TODO Prioridad 1: capturar violaciones de capacidad por ruta.
            String ruta = best.toPathString();
            long tSim = clock != null ? clock.now() : 0L;
            bestSolutionLogger.logRow(tSim, generation, pedidoId, fitnessValue, totalCost,
                    totalTransit, distKm, violSla, violCap, stopovers, avgLoad, ruta);
            return;
        }

        try {
            Path dir = Paths.get("out");
            Files.createDirectories(dir);
            Path file = dir.resolve("best_solution_gen" + generation + "_pedido_" + pedidoId + ".csv");
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
                pw.println("pedido_id,flight_id,origen,destino,dep_min,arr_min");
                for (Vuelo vuelo : best.getRoute()) {
                    pw.printf("%s,%d,%s,%s,%d,%d%n",
                            pedidoId, vuelo.id, vuelo.origen, vuelo.destino, vuelo.salidaMin, vuelo.llegadaMin);
                }
            }
        } catch (IOException e) {
            // Best-effort logging: keep GA running even if export fails.
            e.printStackTrace();
        }
    }

    private int computeWarehouseLimit(Chromosome chromosome, Aeropuerto destino, BusinessRules businessRules) {
        if (chromosome == null || destino == null) {
            return Integer.MAX_VALUE;
        }
        int arrivalMinute = chromosome.getArrivalMinute();
        if (arrivalMinute < 0) {
            return Integer.MAX_VALUE;
        }
        int capacity = destino.capacidad > 0 ? destino.capacidad : 1000;
        NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(destino.codigo);
        int horizon = Math.max(0, businessRules.whBlockMin);
        int slack = capacity;
        for (long minute = arrivalMinute; minute < arrivalMinute + horizon; minute++) {
            int occ = timeline.getOrDefault(minute, 0);
            slack = Math.min(slack, capacity - occ);
            if (slack <= 0) {
                return 0;
            }
        }
        return Math.max(slack, 0);
    }

    public static double fitness(Chromosome chromosome, BusinessRules rules,
                                 PlanningState planningState, FlightCache cache) {
        if (chromosome == null) {
            return LARGE_PENALTY;
        }
        if (!chromosome.isStructurallyFeasible()) {
            RoutesRepairer.repair(chromosome);
            if (!chromosome.isStructurallyFeasible()) {
                chromosome.clearScheduleWindow();
                chromosome.fitness = LARGE_PENALTY;
                return LARGE_PENALTY;
            }
        }
        int[] genes = chromosome.genes();
        if (genes == null || genes.length == 0) {
            chromosome.clearScheduleWindow();
            chromosome.fitness = LARGE_PENALTY;
            return LARGE_PENALTY;
        }
        Pedido pedido = chromosome.getPedido();
        if (pedido == null) {
            chromosome.clearScheduleWindow();
            chromosome.fitness = LARGE_PENALTY;
            return LARGE_PENALTY;
        }

        Vuelo first = cache.flight(genes[0]);
        if (first == null) {
            chromosome.clearScheduleWindow();
            chromosome.fitness = LARGE_PENALTY;
            return LARGE_PENALTY;
        }

        if (!RoutesRepairer.isFeasible(chromosome, planningState)) {
            RoutesRepairer.repair(chromosome);
            if (!RoutesRepairer.isFeasible(chromosome, planningState)) {
                chromosome.clearScheduleWindow();
                chromosome.fitness = LARGE_PENALTY;
                return LARGE_PENALTY;
            }
        }

        int pedidoStart = pedido.dia * MINUTES_PER_DAY + pedido.hora * 60 + pedido.minuto;
        int departure = alignToReference(first.salidaMin, pedidoStart);
        int arrival = alignArrival(first.llegadaMin, departure);
        if (!hasFlightCapacity(first, planningState)) {
            chromosome.clearScheduleWindow();
            chromosome.fitness = LARGE_PENALTY;
            return LARGE_PENALTY;
        }

        double totalCost = 0.0;
        double totalWaiting = 0.0;
        double capacityUsage = 0.0;
        double stopovers = 0.0;
        double totalTransit = 0.0;
        double totalDistanceKm = 0.0;

        for (int i = 0; i < genes.length; i++) {
            int gene = genes[i];
            Vuelo vuelo = cache.flight(gene);
            if (vuelo == null) {
                chromosome.clearScheduleWindow();
                chromosome.fitness = LARGE_PENALTY;
                return LARGE_PENALTY;
            }
            if (!hasFlightCapacity(vuelo, planningState)) {
                chromosome.clearScheduleWindow();
                chromosome.fitness = LARGE_PENALTY;
                return LARGE_PENALTY;
            }
            int segmentDeparture = (i == 0) ? departure : alignAfter(vuelo.salidaMin, arrival);
            int segmentArrival = alignArrival(vuelo.llegadaMin, segmentDeparture);
            double duration = Math.max(0, segmentArrival - segmentDeparture);
            double wait = 0.0;
            if (i > 0) {
                wait = Math.max(0, segmentDeparture - arrival);
                totalWaiting += wait;
            }
            arrival = segmentArrival;
            totalTransit = Math.max(0, arrival - departure);
            double cost = cache.cost(gene);
            double distanceKm = cache.distanceKm(gene);
            double loadRatio = computeCapacityLoad(cache.capacity(gene), pedido.cantidad);
            capacityUsage += loadRatio;
            totalCost += cost;
            totalDistanceKm += distanceKm;
            if (i > 0) {
                stopovers += 1.0;
            }
            chromosome.setSegmentValues(i, duration, wait, cost, loadRatio);
        }

        String origenIata = pedido.hubOrigen != null ? pedido.hubOrigen : first.origen;
        Vuelo lastVuelo = cache.flight(genes[genes.length - 1]);
        String destinoIata = pedido.destino != null ? pedido.destino : (lastVuelo != null ? lastVuelo.destino : null);
        Aeropuerto origenAp = DataLoader.aeropuertos.get(origenIata);
        if (origenAp == null && first != null) {
            origenAp = DataLoader.aeropuertos.get(first.origen);
        }
        Aeropuerto destinoAp = DataLoader.aeropuertos.get(destinoIata);
        if (destinoAp == null && lastVuelo != null) {
            destinoAp = DataLoader.aeropuertos.get(lastVuelo.destino);
        }
        if (origenAp == null || destinoAp == null) {
            chromosome.clearScheduleWindow();
            chromosome.fitness = LARGE_PENALTY;
            return LARGE_PENALTY;
        }

        boolean intra = Objects.equals(origenAp.continente, destinoAp.continente);
        NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(destinoIata);
        int capacity = destinoAp.capacidad > 0 ? destinoAp.capacidad : 1000;
        int block = Math.max(0, rules.whBlockMin);
        boolean warehouseOverflow = false;
        for (long minute = arrival; minute < arrival + block; minute++) {
            int occ = timeline.getOrDefault(minute, 0);
            if (occ >= capacity) {
                warehouseOverflow = true;
                break;
            }
        }
        if (warehouseOverflow) {
            capacityUsage += 1.0; // penalize overflow by increasing load metric
        }

        // BEGIN new fitness
        // Métricas base
        int slaMinutes = (intra ? rules.slaIntraHours : rules.slaInterHours) * 60;
        int departureAbs = departure;
        int arrivalAbs = arrival;
        double totalTransitMinutes = totalTransit;
        double totalWaitingMinutes = totalWaiting;
        double totalCostValue = totalCost;
        double stopoversCount = stopovers;
        double capacityUsageSum = capacityUsage;
        int hops = (int) stopoversCount;
        double avgLoad = capacityUsageSum / Math.max(1.0, genes.length);

        // Tardanza SOLO si excede SLA
        int latenessMin = Math.max(0, arrivalAbs - (departureAbs + slaMinutes));

        // Límites dinámicos (usar cache; añade getters si faltan)
        double p95Transit = Math.max(1, cache.p95TransitMinutes());
        double p95Wait = Math.max(1, cache.p95WaitMinutes());
        double p95Cost = Math.max(1, cache.p95RouteCost());
        int maxHopsRef = Math.max(1, cache.maxHopsRef());
        // Fallbacks razonables
        if (p95Transit == 1) {
            p95Transit = Math.max(cache.estimateMaxRouteDuration(), slaMinutes);
        }
        if (p95Wait == 1) {
            p95Wait = 6 * 60.0;
        }
        if (p95Cost == 1) {
            p95Cost = Math.max(cache.estimateMaxRouteCost(), totalCostValue + 100);
        }

        // Normalizaciones 0..1 (cap outliers)
        double latenessS = Math.min(1.0, (double) latenessMin / (double) slaMinutes);
        double transitS = Math.min(1.0, totalTransitMinutes / p95Transit);
        double waitS = Math.min(1.0, totalWaitingMinutes / p95Wait);
        double costS = Math.min(1.0, totalCostValue / p95Cost);
        double hopsS = Math.min(1.0, (double) hops / (double) maxHopsRef);

        // Capacidad: preferencia en 90% (cuanto más lejos, peor)
        double targetLoad = 0.90, capSigma = 0.10;
        double capS = Math.min(1.0, Math.abs(avgLoad - targetLoad) / capSigma);

        // Puntuaciones (menor = mejor)
        double latenessScore = Math.pow(latenessS, 1.3);
        double transitScore = transitS * 0.5;
        double waitScore = waitS;
        double costScore = costS;
        double hopsScore = hopsS;
        double capScore = capS;

        // Pesos (suman ≈ 1)
        double wLate = 0.40, wCost = 0.20, wWait = 0.15, wCap = 0.10, wHops = 0.10, wTransit = 0.05;

        double baseNormalized = wLate * latenessScore + wCost * costScore + wWait * waitScore
                + wCap * capScore + wHops * hopsScore + wTransit * transitScore;
        double weightedCtd = COST_WEIGHT * totalCostValue + TIME_WEIGHT * totalTransitMinutes
                + DISTANCE_WEIGHT * totalDistanceKm;
        double fitness = baseNormalized + weightedCtd;

        chromosome.setScheduleWindow(departureAbs, arrivalAbs);
        chromosome.setPunctualityMinutes(totalTransitMinutes);
        chromosome.setSlaDelayMinutes(latenessMin);
        chromosome.setTotalCost(totalCostValue);
        chromosome.setWaitingMinutes(totalWaitingMinutes);
        chromosome.setCapacityUsage(avgLoad);
        chromosome.setStopovers(stopoversCount);
        chromosome.setTotalDistanceKm(totalDistanceKm);
        chromosome.clearDirty();
        chromosome.fitness = fitness;
        return fitness;
        // END new fitness
    }

    private static boolean hasFlightCapacity(Vuelo vuelo, PlanningState planningState) {
        Integer cap = planningState.flightCapacity().get(flightKey(vuelo));
        if (cap == null) {
            cap = vuelo.capacidad;
        }
        return cap == null || cap > 0;
    }

    private static double computeCapacityLoad(int flightCapacity, int demanda) {
        if (flightCapacity <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, demanda / (double) flightCapacity));
    }

    private static int alignToReference(int minuteOfDay, int referenceAbs) {
        int base = referenceAbs - (referenceAbs % MINUTES_PER_DAY);
        int candidate = base + minuteOfDay;
        while (candidate < referenceAbs) {
            candidate += MINUTES_PER_DAY;
        }
        return candidate;
    }

    private static int alignAfter(int minuteOfDay, int earliestAbs) {
        int base = earliestAbs - (earliestAbs % MINUTES_PER_DAY);
        int candidate = base + minuteOfDay;
        while (candidate < earliestAbs) {
            candidate += MINUTES_PER_DAY;
        }
        return candidate;
    }

    private static int alignArrival(int minuteOfDay, int departureAbs) {
        int base = departureAbs - (departureAbs % MINUTES_PER_DAY);
        int candidate = base + minuteOfDay;
        while (candidate < departureAbs) {
            candidate += MINUTES_PER_DAY;
        }
        return candidate;
    }

    public static final class EvolutionOutcome {
        public final List<Chromosome> population;
        public final Chromosome best;
        public final double fitness;
        public final int generation;

        EvolutionOutcome(List<Chromosome> population, Chromosome best, double fitness, int generation) {
            this.population = population;
            this.best = best;
            this.fitness = fitness;
            this.generation = generation;
        }
    }

    private static final class PopulationStats {
        double best;
        double avg;
        double worst;
        double feasibleRatio;
        Chromosome bestChromosome;
        double p95Transit;
        double p95Wait;
        double p95Cost;
        int maxHopsRef;
    }
}
