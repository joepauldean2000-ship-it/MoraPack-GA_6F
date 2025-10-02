package com.morapack.ga.core;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class PlanningState {
    private final Map<String, Integer> flightCapacity = new HashMap<>();
    private final Map<String, NavigableMap<Long, Integer>> warehouses = new HashMap<>();

    public Map<String, Integer> flightCapacity() {
        return flightCapacity;
    }

    public Map<String, NavigableMap<Long, Integer>> warehouses() {
        return warehouses;
    }

    public NavigableMap<Long, Integer> warehouseTimeline(String iata) {
        return warehouses.computeIfAbsent(iata, k -> new TreeMap<>());
    }
}
