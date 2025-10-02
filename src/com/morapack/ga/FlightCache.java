package com.morapack.ga;

import java.util.ArrayList;
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
}
