package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class Chromosome {
    private final FlightCache cache;
    private int[] genes;
    private List<Vuelo> routeCache;
    double fitness = Double.NaN;
    private boolean structurallyFeasible = true;
    private Pedido pedido;
    private int departureMinute = -1;
    private int arrivalMinute = -1;
    private boolean dirty = true;
    private int dirtyFrom = 0;
    private int dirtyTo = -1;

    private double punctualityMinutes;
    private double slaDelayMinutes;
    private double totalCost;
    private double waitingMinutes;
    private double capacityUsage;
    private double stopovers;
    private double[] segmentDuration = new double[0];
    private double[] segmentWaiting = new double[0];
    private double[] segmentCost = new double[0];
    private double[] segmentLoad = new double[0];

    public Chromosome(FlightCache cache, List<Vuelo> route) {
        this.cache = cache;
        setRoute(route);
    }

    public Chromosome(FlightCache cache, int[] genes) {
        this.cache = cache;
        setGenes(genes);
    }

    public static Chromosome ofRoute(FlightCache cache, Pedido pedido, List<Vuelo> route) {
        if (cache == null || pedido == null || route == null || route.isEmpty()) {
            return null;
        }
        Chromosome chromosome = new Chromosome(cache, route);
        chromosome.setPedido(pedido);
        return chromosome;
    }

    public static Chromosome randomInit(List<Pedido> pedidos, GraphVuelos graph,
                                        PlanningState state, FlightCache cache, Random random) {
        if (pedidos == null || pedidos.isEmpty() || graph == null || random == null || cache == null) {
            return null;
        }
        int attempts = Math.max(10, pedidos.size() * 4);
        for (int attempt = 0; attempt < attempts; attempt++) {
            Pedido pedido = pedidos.get(random.nextInt(pedidos.size()));
            Chromosome candidate = randomForPedido(pedido, graph, state, cache, random);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Chromosome randomForPedido(Pedido pedido, GraphVuelos graph,
                                              PlanningState state, FlightCache cache, Random random) {
        if (pedido == null) {
            return null;
        }
        String origin = pedido.hubOrigen;
        String destination = pedido.destino;
        if (origin == null || destination == null || origin.equals(destination)) {
            return null;
        }

        int maxSteps = Math.max(3, graph.nodeCount());
        String current = origin;
        Set<String> visited = new HashSet<>();
        visited.add(current);
        List<Vuelo> path = new ArrayList<>();

        for (int step = 0; step < maxSteps; step++) {
            List<Vuelo> outgoing = new ArrayList<>(graph.outgoing(current));
            if (outgoing.isEmpty()) {
                break;
            }
            Collections.shuffle(outgoing, random);
            boolean extended = false;
            for (Vuelo vuelo : outgoing) {
                if (!hasResidualCapacity(vuelo, state)) {
                    continue;
                }
                if (visited.contains(vuelo.destino) && !vuelo.destino.equals(destination)) {
                    continue;
                }
                path.add(vuelo);
                current = vuelo.destino;
                visited.add(current);
                extended = true;
                break;
            }
            if (!extended) {
                break;
            }
            if (current.equals(destination)) {
                Chromosome chromosome = new Chromosome(cache, path);
                chromosome.setPedido(pedido);
                return chromosome;
            }
        }
        return null;
    }

    private static boolean hasResidualCapacity(Vuelo vuelo, PlanningState state) {
        if (state == null) {
            return true;
        }
        Integer cap = state.flightCapacity().get(String.valueOf(vuelo.id));
        if (cap == null) {
            cap = vuelo.capacidad;
        }
        return cap == null || cap > 0;
    }

    public int length() {
        return genes == null ? 0 : genes.length;
    }

    public int geneAt(int index) {
        if (genes == null || index < 0 || index >= genes.length) {
            return -1;
        }
        return genes[index];
    }

    public int[] genes() {
        return genes;
    }

    public void setGenes(int[] newGenes) {
        if (newGenes == null) {
            this.genes = new int[0];
        } else {
            this.genes = Arrays.copyOf(newGenes, newGenes.length);
        }
        this.routeCache = null;
        resizeSegmentArrays(length());
        markDirty(0, length() - 1);
    }

    public void swap(int i, int j) {
        if (genes == null || i < 0 || j < 0 || i >= genes.length || j >= genes.length || i == j) {
            return;
        }
        int tmp = genes[i];
        genes[i] = genes[j];
        genes[j] = tmp;
        this.routeCache = null;
        markDirty(Math.min(i, j), Math.max(i, j));
    }

    public void reverse(int from, int to) {
        if (genes == null || from < 0 || to < 0 || from >= genes.length || to >= genes.length || from >= to) {
            return;
        }
        while (from < to) {
            swap(from, to);
            from++;
            to--;
        }
    }

    public void shuffleSegment(int from, int to, Random random) {
        if (genes == null || from < 0 || to < 0 || from >= genes.length || to >= genes.length || from >= to) {
            return;
        }
        for (int i = to; i > from; i--) {
            int j = from + random.nextInt(i - from + 1);
            swap(i, j);
        }
    }

    public void rotateThree(int a, int b, int c) {
        if (genes == null || a < 0 || b < 0 || c < 0 || a >= genes.length || b >= genes.length || c >= genes.length) {
            return;
        }
        int tmp = genes[a];
        genes[a] = genes[b];
        genes[b] = genes[c];
        genes[c] = tmp;
        this.routeCache = null;
        markDirty(Math.min(a, Math.min(b, c)), Math.max(a, Math.max(b, c)));
    }

    public List<Vuelo> getRoute() {
        if (routeCache == null) {
            routeCache = new ArrayList<>(length());
            if (genes != null) {
                for (int gene : genes) {
                    if (gene >= 0 && gene < cache.size()) {
                        routeCache.add(cache.flight(gene));
                    }
                }
            }
        }
        return new ArrayList<>(routeCache);
    }

    public void setRoute(List<Vuelo> route) {
        if (route == null) {
            this.genes = new int[0];
            this.routeCache = new ArrayList<>();
        } else {
            this.routeCache = new ArrayList<>(route);
            this.genes = new int[route.size()];
            for (int i = 0; i < route.size(); i++) {
                Vuelo vuelo = route.get(i);
                int idx = cache.indexOf(vuelo);
                this.genes[i] = idx;
            }
        }
        resizeSegmentArrays(length());
        markDirty(0, length() - 1);
    }

    public boolean isStructurallyFeasible() {
        return structurallyFeasible && length() > 0;
    }

    public void setStructurallyFeasible(boolean structurallyFeasible) {
        this.structurallyFeasible = structurallyFeasible;
        if (!structurallyFeasible) {
            clearScheduleWindow();
        }
    }

    public Pedido getPedido() {
        return pedido;
    }

    public void setPedido(Pedido pedido) {
        this.pedido = pedido;
    }

    public void clearScheduleWindow() {
        this.departureMinute = -1;
        this.arrivalMinute = -1;
    }

    public void setScheduleWindow(int departureMinute, int arrivalMinute) {
        this.departureMinute = departureMinute;
        this.arrivalMinute = arrivalMinute;
    }

    public int getDepartureMinute() {
        return departureMinute;
    }

    public int getArrivalMinute() {
        return arrivalMinute;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int getDirtyFrom() {
        return dirtyFrom;
    }

    public int getDirtyTo() {
        return dirtyTo;
    }

    public void clearDirty() {
        this.dirty = false;
        this.dirtyFrom = 0;
        this.dirtyTo = -1;
    }

    public void markDirty(int from, int to) {
        this.dirty = true;
        this.dirtyFrom = Math.max(0, from);
        this.dirtyTo = Math.max(this.dirtyFrom, to);
    }

    private void resizeSegmentArrays(int length) {
        if (length < 0) {
            length = 0;
        }
        this.segmentDuration = new double[length];
        this.segmentWaiting = new double[length];
        this.segmentCost = new double[length];
        this.segmentLoad = new double[length];
    }

    public void setSegmentValues(int index, double duration, double wait, double cost, double load) {
        if (index < 0 || index >= segmentDuration.length) {
            return;
        }
        segmentDuration[index] = duration;
        segmentWaiting[index] = wait;
        segmentCost[index] = cost;
        segmentLoad[index] = load;
    }

    public double[] segmentDuration() {
        return segmentDuration;
    }

    public double[] segmentWaiting() {
        return segmentWaiting;
    }

    public double[] segmentCost() {
        return segmentCost;
    }

    public double[] segmentLoad() {
        return segmentLoad;
    }

    public void setPunctualityMinutes(double punctualityMinutes) {
        this.punctualityMinutes = punctualityMinutes;
    }

    public double getPunctualityMinutes() {
        return punctualityMinutes;
    }

    public void setSlaDelayMinutes(double slaDelayMinutes) {
        this.slaDelayMinutes = slaDelayMinutes;
    }

    public double getSlaDelayMinutes() {
        return slaDelayMinutes;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setWaitingMinutes(double waitingMinutes) {
        this.waitingMinutes = waitingMinutes;
    }

    public double getWaitingMinutes() {
        return waitingMinutes;
    }

    public void setCapacityUsage(double capacityUsage) {
        this.capacityUsage = capacityUsage;
    }

    public double getCapacityUsage() {
        return capacityUsage;
    }

    public void setStopovers(double stopovers) {
        this.stopovers = stopovers;
    }

    public double getStopovers() {
        return stopovers;
    }

    public FlightCache getCache() {
        return cache;
    }

    public String toPathString() {
        List<Vuelo> route = getRoute();
        if (route.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Vuelo first = route.get(0);
        sb.append(nodeLabel(first != null ? first.origen : null, first != null ? first.id : -1));
        sb.append("->");
        sb.append(nodeLabel(first != null ? first.destino : null, first != null ? first.id : -1));
        for (int i = 1; i < route.size(); i++) {
            Vuelo vuelo = route.get(i);
            sb.append("->");
            sb.append(nodeLabel(vuelo != null ? vuelo.destino : null, vuelo != null ? vuelo.id : -1));
        }
        return sb.toString();
    }

    private String nodeLabel(String code, int fallbackId) {
        if (code != null && !code.isBlank()) {
            return code;
        }
        return fallbackId >= 0 ? "FL" + fallbackId : "";
    }

    @Override
    public String toString() {
        List<Vuelo> r = getRoute();
        return "Ruta=" + r + " | Fitness=" + (Double.isNaN(fitness) ? "NaN" : String.format("%.4f", fitness));
    }
}
