package com.morapack.ga;

import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
    private static final double WEIGHT_PUNCTUALITY = 0.45;
    private static final double WEIGHT_COST = 0.20;
    private static final double WEIGHT_WAITING = 0.15;
    private static final double WEIGHT_CAPACITY = 0.10;
    private static final double WEIGHT_STOPOVERS = 0.10;
    private static final double MIN_MUTATION = 0.04;
    private static final double MAX_MUTATION = 0.15;

    private final PlanningState planningState;
    private final SimClock clock;
    private final PlanLogger logger;
    private final Random random = new Random();
    private long rngSeed;

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

        rngSeed = System.currentTimeMillis();
        random.setSeed(rngSeed);
        logger.logMetric("ga.seed", rngSeed, clock.now());
        System.out.println("GA RNG seed: " + rngSeed);

        Map<String, Integer> capRest = planningState.flightCapacity();
        capRest.clear();
        for (Vuelo v : vuelosDisponibles) {
            capRest.put(flightKey(v), v.capacidad);
        }

        FlightCache cache = new FlightCache(vuelosDisponibles);
        GraphVuelos graph = new GraphVuelos(vuelosDisponibles);
        Heuristic greedyByDuration = (pedido, g, st, c) -> {
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

        int totalSolicitados = 0;
        int totalAsignados = 0;
        int totalPendientes = 0;

        try (PrintWriter writer = new PrintWriter("plan_asignacion_GA.csv")) {
            writer.println("pedido_id,dia,hub_origen,destino,ruta,asignados,pendientes,fitness");

            logger.logMetric("startup", 1.0, clock.now());

            for (Pedido p : pedidos) {
                totalSolicitados += p.cantidad;
                int restantes = p.cantidad;
                int asignadosTotal = 0;

                while (restantes > 0) {
                    List<Chromosome> initialPopulation = PopulationInitializer.initPopulation(
                            params.popSize,
                            greedyByDuration,
                            Collections.singletonList(p),
                            graph,
                            planningState,
                            cache,
                            random);

                    if (initialPopulation.isEmpty()) {
                        break;
                    }

                    EvolutionOutcome outcome = evolve(initialPopulation, params, businessRules, logger, clock,
                            metricsCsv, cache, greedyByDuration, Collections.singletonList(p), graph, p);
                    Chromosome best = outcome.best;

                    if (best == null || best.getRoute().isEmpty() || !Double.isFinite(best.fitness)) {
                        break;
                    }

                    int cuello = Integer.MAX_VALUE;
                    for (Vuelo v : best.getRoute()) {
                        cuello = Math.min(cuello, capRest.getOrDefault(flightKey(v), v.capacidad));
                    }
                    if (cuello <= 0) {
                        break;
                    }

                    Aeropuerto apDest = aeropuertos.get(p.destino);
                    int warehouseLimit = computeWarehouseLimit(best, apDest, businessRules);
                    if (warehouseLimit <= 0) {
                        break;
                    }

                    int asignados = Math.min(restantes, Math.min(cuello, warehouseLimit));
                    if (asignados <= 0) {
                        break;
                    }

                    restantes -= asignados;
                    asignadosTotal += asignados;

                    for (Vuelo v : best.getRoute()) {
                        String key = flightKey(v);
                        capRest.put(key, capRest.get(key) - asignados);
                    }

                    int arrivalMinute = best.getArrivalMinute();
                    if (arrivalMinute >= 0 && apDest != null) {
                        clock.advanceTo(arrivalMinute);
                        NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(p.destino);
                        for (long m = arrivalMinute; m < arrivalMinute + businessRules.whBlockMin; m++) {
                            timeline.put(m, timeline.getOrDefault(m, 0) + asignados);
                        }
                    }

                    writer.printf("%s,%d,%s,%s,\"%s\",%d,%d,%.4f%n",
                            p.id, p.dia, p.hubOrigen, p.destino,
                            best.getRoute().toString(), asignados, restantes, best.fitness);

                    logger.logMetric("pedido_asignado", asignados, clock.now());

                    if (restantes == 0) {
                        break;
                    }
                }

                totalAsignados += asignadosTotal;
                totalPendientes += restantes;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\n=== Resumen GA ===");
        System.out.println("Pedidos totales: " + pedidos.size());
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
                        stats.feasibleRatio, mutationRate, diversity));
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
        }
        stats.best = best;
        stats.worst = worst;
        stats.avg = sum / population.size();
        stats.feasibleRatio = population.isEmpty() ? Double.NaN : ((double) feasible) / population.size();
        stats.bestChromosome = bestChrom != null ? bestChrom : population.get(0);
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
        try {
            Path dir = Paths.get("out");
            Files.createDirectories(dir);
            String pedidoId = pedidoContext != null ? pedidoContext.id : "NA";
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
        double slaDelay = 0.0;
        double totalTransit = 0.0;

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
            double loadRatio = computeCapacityLoad(cache.capacity(gene), pedido.cantidad);
            capacityUsage += loadRatio;
            totalCost += cost;
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
        int slaMinutes = (intra ? rules.slaIntraHours : rules.slaInterHours) * 60;
        if (totalTransit > slaMinutes) {
            slaDelay = totalTransit - slaMinutes;
        }

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

        double punctualityMinutes = totalTransit + slaDelay;
        double normalizedPunctuality = punctualityMinutes / Math.max(cache.estimateMaxRouteDuration(), slaMinutes);
        double normalizedCost = totalCost / Math.max(cache.estimateMaxRouteCost(), totalCost + 1);
        double normalizedWaiting = totalWaiting / (12 * 60.0);
        double normalizedCapacity = capacityUsage / Math.max(1.0, genes.length);
        double normalizedStops = stopovers / 6.0;

        double fitness = WEIGHT_PUNCTUALITY * normalizedPunctuality
                + WEIGHT_COST * normalizedCost
                + WEIGHT_WAITING * normalizedWaiting
                + WEIGHT_CAPACITY * normalizedCapacity
                + WEIGHT_STOPOVERS * normalizedStops
                + (slaDelay / (double) MINUTES_PER_DAY);

        chromosome.setScheduleWindow(departure, arrival);
        chromosome.setPunctualityMinutes(totalTransit);
        chromosome.setSlaDelayMinutes(slaDelay);
        chromosome.setTotalCost(totalCost);
        chromosome.setWaitingMinutes(totalWaiting);
        chromosome.setCapacityUsage(normalizedCapacity);
        chromosome.setStopovers(stopovers);
        chromosome.clearDirty();
        chromosome.fitness = fitness;
        return fitness;
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
    }
}
