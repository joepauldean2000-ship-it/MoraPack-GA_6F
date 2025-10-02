package com.morapack.ga;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable cache over flight attributes so GA operators can access indexed metadata quickly.
 */
public final class FlightCache {
    private static final double BASE_COST = 180.0;
    private static final double COST_PER_MIN = 0.65;

    private final List<Vuelo> flights;
    private final Map<Integer, Integer> idToIndex;
    private final Map<String, List<Integer>> outgoing;
    private final double[] durationMinutes;
    private final double[] cost;
    private final int[] departureMinutes;
    private final int[] arrivalMinutes;
    private final int[] capacity;
    private final boolean[] intra;
    private final double maxCost;
    private final double maxDuration;
    private final double p95TransitMinutes;
    private final double p95WaitMinutes;
    private final double p95RouteCost;
    private final int maxHopsRef;

    public FlightCache(List<Vuelo> flights) {
        this.flights = new ArrayList<>(Objects.requireNonNull(flights, "flights"));
        this.idToIndex = new HashMap<>(flights.size());
        this.outgoing = new HashMap<>();
        this.durationMinutes = new double[flights.size()];
        this.cost = new double[flights.size()];
        this.departureMinutes = new int[flights.size()];
        this.arrivalMinutes = new int[flights.size()];
        this.capacity = new int[flights.size()];
        this.intra = new boolean[flights.size()];

        double localMaxCost = 0.0;
        double localMaxDuration = 0.0;
        for (int i = 0; i < flights.size(); i++) {
            Vuelo v = flights.get(i);
            idToIndex.put(v.id, i);
            outgoing.computeIfAbsent(v.origen, k -> new ArrayList<>()).add(i);
            double duration = computeDurationMinutes(v);
            durationMinutes[i] = duration;
            cost[i] = BASE_COST + duration * COST_PER_MIN;
            departureMinutes[i] = v.salidaMin;
            arrivalMinutes[i] = v.llegadaMin;
            capacity[i] = v.capacidad;
            intra[i] = v.esContinental;
            localMaxCost = Math.max(localMaxCost, cost[i]);
            localMaxDuration = Math.max(localMaxDuration, duration);
        }
        for (List<Integer> edges : outgoing.values()) {
            edges.sort((a, b) -> Integer.compare(departureMinutes[a], departureMinutes[b]));
        }
        this.maxCost = localMaxCost <= 0 ? BASE_COST : localMaxCost;
        this.maxDuration = localMaxDuration <= 0 ? 60.0 : localMaxDuration;

        this.p95TransitMinutes = computePercentile(durationMinutes, 0.95);
        this.p95RouteCost = computePercentile(cost, 0.95);
        List<Double> waitSamples = new ArrayList<>();
        for (int i = 0; i < flights.size(); i++) {
            Vuelo flight = flights.get(i);
            List<Integer> candidates = outgoing.get(flight.destino);
            if (candidates == null) {
                continue;
            }
            for (int idx : candidates) {
                waitSamples.add(waitingMinutes(i, idx));
            }
        }
        this.p95WaitMinutes = waitSamples.isEmpty() ? 0.0 : computePercentile(waitSamples, 0.95);
        int approxHops = (int) Math.ceil(Math.sqrt(Math.max(1, flights.size())));
        this.maxHopsRef = Math.max(1, Math.min(8, approxHops));
    }

    private static double computeDurationMinutes(Vuelo v) {
        int diff = v.llegadaMin - v.salidaMin;
        if (diff < 0) {
            diff += 24 * 60;
        }
        return diff;
    }

    public int size() {
        return flights.size();
    }

    public Vuelo flight(int index) {
        return flights.get(index);
    }

    public Vuelo byId(int id) {
        Integer idx = idToIndex.get(id);
        return idx == null ? null : flights.get(idx);
    }

    public int indexOf(Vuelo vuelo) {
        if (vuelo == null) {
            return -1;
        }
        Integer idx = idToIndex.get(vuelo.id);
        return idx == null ? -1 : idx;
    }

    public double durationMinutes(int index) {
        return durationMinutes[index];
    }

    public double cost(int index) {
        return cost[index];
    }

    public int departureMinute(int index) {
        return departureMinutes[index];
    }

    public int arrivalMinute(int index) {
        return arrivalMinutes[index];
    }

    public int capacity(int index) {
        return capacity[index];
    }

    public boolean isIntra(int index) {
        return intra[index];
    }

    public List<Integer> outgoing(String origin) {
        List<Integer> edges = outgoing.get(origin);
        if (edges == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(edges);
    }

    public double waitingMinutes(int fromIndex, int toIndex) {
        int arr = arrivalMinutes[fromIndex];
        int dep = departureMinutes[toIndex];
        int wait = dep - arr;
        while (wait < 0) {
            wait += 24 * 60;
        }
        return wait;
    }

    public double maxCost() {
        return maxCost;
    }

    public double maxDuration() {
        return maxDuration;
    }

    public double estimateMaxRouteCost() {
        return maxCost * 6.0;
    }

    public double estimateMaxRouteDuration() {
        return maxDuration * 6.0;
    }

    public double p95TransitMinutes() {
        return p95TransitMinutes;
    }

    public double p95WaitMinutes() {
        return p95WaitMinutes;
    }

    public double p95RouteCost() {
        return p95RouteCost;
    }

    public int maxHopsRef() {
        return maxHopsRef;
    }

    private static double computePercentile(double[] values, double percentile) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return interpolatePercentile(copy, percentile);
    }

    private static double computePercentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double[] copy = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            copy[i] = values.get(i);
        }
        Arrays.sort(copy);
        return interpolatePercentile(copy, percentile);
    }

    private static double interpolatePercentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, percentile));
        double position = clamped * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }
}
