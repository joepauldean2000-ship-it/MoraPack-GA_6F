package com.morapack.ga;

import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.ToDoubleFunction;

public class GeneticAlgorithm {
    private static final int MINUTES_PER_DAY = 24 * 60;

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
                    GaMetricsCsv metricsCsv, BusinessRules businessRules) {

        Map<String, Integer> capRest = planningState.flightCapacity();
        capRest.clear();
        for (Vuelo v : vuelosDisponibles) {
            capRest.put(flightKey(v), v.capacidad);
        }

        GraphVuelos graph = new GraphVuelos(vuelosDisponibles);
        Heuristic greedyByDuration = (pedido, g, st) -> {
            if (pedido == null || g == null) {
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
            Chromosome chromosome = Chromosome.ofRoute(pedido, path);
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
                            planningState);

                    if (initialPopulation.isEmpty()) {
                        break;
                    }

                    List<Chromosome> finalPopulation = evolve(initialPopulation, params, businessRules, logger, clock, metricsCsv);

                    Chromosome best = finalPopulation.stream()
                            .filter(ch -> ch != null && ch.getRoute() != null && !ch.getRoute().isEmpty())
                            .filter(ch -> Double.isFinite(ch.fitness))
                            .min(Comparator.comparingDouble(ch -> ch.fitness))
                            .orElse(null);

                    if (best == null) {
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

                    writer.printf("%s,%d,%s,%s,\"%s\",%d,%d,%.2f%n",
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

    public Chromosome tournamentSelect(List<Chromosome> pop, int k,
                                       ToDoubleFunction<Chromosome> fitnessFn) {
        if (pop == null || pop.isEmpty()) {
            return null;
        }
        int sampleSize = Math.max(1, Math.min(k, pop.size()));
        Set<Integer> picked = new HashSet<>();
        Chromosome best = null;
        double bestFitness = Double.POSITIVE_INFINITY;
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

    public Chromosome crossoverOnePoint(Chromosome a, Chromosome b) {
        if (a == null) {
            return null;
        }
        if (b == null || a.route.size() < 2 || b.route.isEmpty()) {
            Chromosome clone = copyOf(a);
            clone.fitness = Double.NaN;
            return clone;
        }
        int maxCut = Math.min(a.route.size(), Math.max(1, b.route.size()));
        if (maxCut < 2) {
            Chromosome clone = copyOf(a);
            clone.fitness = Double.NaN;
            return clone;
        }
        int cut = 1 + random.nextInt(maxCut - 1);
        List<Vuelo> childRoute = new ArrayList<>(a.route.subList(0, Math.min(cut, a.route.size())));
        for (Vuelo vuelo : b.route) {
            if (!childRoute.contains(vuelo)) {
                childRoute.add(vuelo);
            }
        }
        Chromosome child = new Chromosome(childRoute);
        child.setPedido(a.getPedido());
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
                                   BusinessRules businessRules,
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

        ToDoubleFunction<Chromosome> fitnessFn = c -> fitness(c, businessRules, planningState);

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
                sorted.sort(Comparator.comparingDouble((Chromosome c) -> c.fitness));
                for (int i = 0; i < eliteCount && next.size() < popSize && i < sorted.size(); i++) {
                    Chromosome eliteCopy = copyOf(sorted.get(i));
                    RoutesRepairer.repair(eliteCopy);
                    next.add(eliteCopy);
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
                    child.fitness = Double.NaN;
                } else if (random.nextDouble() < params.pCrossover) {
                    child = crossoverOnePoint(parent1, parent2);
                } else {
                    child = copyOf(parent1);
                    child.fitness = Double.NaN;
                }
                mutateSwap(child, params.pMutation);
                if (child != null) {
                    RoutesRepairer.repair(child);
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
        copy.setStructurallyFeasible(c.isStructurallyFeasible());
        copy.setPedido(c.getPedido());
        copy.setScheduleWindow(c.getDepartureMinute(), c.getArrivalMinute());
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

        double best = Double.POSITIVE_INFINITY;
        double worst = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        int feasible = 0;
        for (Chromosome chromosome : population) {
            double fitness = fitnessFn.applyAsDouble(chromosome);
            chromosome.fitness = fitness;
            if (fitness < best) {
                best = fitness;
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
        return stats;
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

    public static double fitness(Chromosome chromosome, BusinessRules rules, PlanningState planningState) {
        if (chromosome == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (!chromosome.isStructurallyFeasible()) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }
        List<Vuelo> route = chromosome.getRoute();
        if (route == null || route.isEmpty()) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }
        Pedido pedido = chromosome.getPedido();
        if (pedido == null) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }

        Vuelo first = route.get(0);
        int pedidoStart = pedido.dia * MINUTES_PER_DAY + pedido.hora * 60 + pedido.minuto;
        int departure = alignToReference(first.salidaMin, pedidoStart);
        int arrival = alignArrival(first.llegadaMin, departure);

        // Capacidad de vuelo: residual <= 0 implica ruta inviable.
        if (!hasFlightCapacity(first, planningState)) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }

        int currentArrival = arrival;
        for (int i = 1; i < route.size(); i++) {
            Vuelo vuelo = route.get(i);
            int dep = alignAfter(vuelo.salidaMin, currentArrival);
            int arr = alignArrival(vuelo.llegadaMin, dep);
            if (!hasFlightCapacity(vuelo, planningState)) {
                chromosome.clearScheduleWindow();
                return Double.POSITIVE_INFINITY;
            }
            currentArrival = arr;
        }

        int totalMinutes = Math.max(0, currentArrival - departure);

        String origenIata = pedido.hubOrigen != null ? pedido.hubOrigen : first.origen;
        String destinoIata = pedido.destino != null ? pedido.destino : route.get(route.size() - 1).destino;
        Aeropuerto origenAp = DataLoader.aeropuertos.get(origenIata);
        if (origenAp == null) {
            origenAp = DataLoader.aeropuertos.get(first.origen);
        }
        Aeropuerto destinoAp = DataLoader.aeropuertos.get(destinoIata);
        if (destinoAp == null && !route.isEmpty()) {
            destinoAp = DataLoader.aeropuertos.get(route.get(route.size() - 1).destino);
        }
        if (origenAp == null || destinoAp == null) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }

        boolean intra = Objects.equals(origenAp.continente, destinoAp.continente);
        // SLA fijo: 48h intra-continente y 72h inter-continente (hard constraint).
        int slaMinutes = (intra ? rules.slaIntraHours : rules.slaInterHours) * 60;
        if (totalMinutes > slaMinutes) {
            chromosome.clearScheduleWindow();
            return Double.POSITIVE_INFINITY;
        }

        // Almacén: bloquear 120 minutos y fallar si se supera la capacidad permitida.
        NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(destinoIata);
        int capacity = destinoAp.capacidad > 0 ? destinoAp.capacidad : 1000;
        int block = Math.max(0, rules.whBlockMin);
        for (long minute = currentArrival; minute < currentArrival + block; minute++) {
            int occ = timeline.getOrDefault(minute, 0);
            if (occ >= capacity) {
                chromosome.clearScheduleWindow();
                return Double.POSITIVE_INFINITY;
            }
        }

        chromosome.setScheduleWindow(departure, currentArrival);
        return totalMinutes / 60.0;
    }

    private static boolean hasFlightCapacity(Vuelo vuelo, PlanningState planningState) {
        Integer cap = planningState.flightCapacity().get(flightKey(vuelo));
        if (cap == null) {
            cap = vuelo.capacidad;
        }
        return cap != null && cap > 0;
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

    private static final class PopulationStats {
        double best;
        double avg;
        double worst;
        double feasibleRatio;
    }
}
