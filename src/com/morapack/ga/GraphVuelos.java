package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Directed graph over the available flights used by greedy builders.
 */
public final class GraphVuelos {
    private final Map<String, List<Vuelo>> outgoing = new HashMap<>();

    public GraphVuelos(Collection<Vuelo> vuelos) {
        if (vuelos == null) {
            return;
        }
        for (Vuelo vuelo : vuelos) {
            outgoing.computeIfAbsent(vuelo.origen, k -> new ArrayList<>()).add(vuelo);
        }
        for (List<Vuelo> edges : outgoing.values()) {
            edges.sort(Comparator.comparingInt(v -> v.salidaMin));
        }
    }

    public List<Vuelo> outgoing(String origin) {
        List<Vuelo> edges = outgoing.get(origin);
        if (edges == null || edges.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(edges);
    }

    public int nodeCount() {
        return outgoing.size();
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(outgoing.keySet());
    }

    /**
     * Dijkstra-style search minimizing total flight duration (in hours) while skipping flights without
     * residual capacity in the planning state. It ignores layover wait time; downstream repair and
     * fitness enforce chronology.
     */
    public List<Vuelo> shortestPathByDuration(String origin, String destination, PlanningState state) {
        if (origin == null || destination == null) {
            return Collections.emptyList();
        }
        if (Objects.equals(origin, destination)) {
            return Collections.emptyList();
        }

        PriorityQueue<State> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> s.cost));
        Map<String, Double> dist = new HashMap<>();
        Map<String, Vuelo> prevFlight = new HashMap<>();

        dist.put(origin, 0.0);
        pq.add(new State(origin, 0.0));

        while (!pq.isEmpty()) {
            State stateNode = pq.poll();
            double known = dist.getOrDefault(stateNode.node, Double.POSITIVE_INFINITY);
            if (stateNode.cost > known) {
                continue;
            }
            if (stateNode.node.equals(destination)) {
                break;
            }

            for (Vuelo edge : outgoing(stateNode.node)) {
                if (!hasResidualCapacity(edge, state)) {
                    continue;
                }
                double durationHours = edge.horasDuracion > 0 ? edge.horasDuracion : computeDurationHours(edge);
                double nextCost = stateNode.cost + durationHours;
                String nextNode = edge.destino;
                if (nextCost < dist.getOrDefault(nextNode, Double.POSITIVE_INFINITY)) {
                    dist.put(nextNode, nextCost);
                    prevFlight.put(nextNode, edge);
                    pq.add(new State(nextNode, nextCost));
                }
            }
        }

        if (!dist.containsKey(destination)) {
            return Collections.emptyList();
        }

        List<Vuelo> path = new ArrayList<>();
        String cursor = destination;
        while (!cursor.equals(origin)) {
            Vuelo edge = prevFlight.get(cursor);
            if (edge == null) {
                return Collections.emptyList();
            }
            path.add(edge);
            cursor = edge.origen;
        }
        Collections.reverse(path);
        return path;
    }

    private boolean hasResidualCapacity(Vuelo vuelo, PlanningState state) {
        if (state == null) {
            return true;
        }
        Integer cap = state.flightCapacity().get(String.valueOf(vuelo.id));
        if (cap == null) {
            cap = vuelo.capacidad;
        }
        return cap == null || cap > 0;
    }

    private static double computeDurationHours(Vuelo vuelo) {
        int duration = vuelo.llegadaMin - vuelo.salidaMin;
        if (duration < 0) {
            duration += 24 * 60;
        }
        return duration / 60.0;
    }

    private static final class State {
        final String node;
        final double cost;

        State(String node, double cost) {
            this.node = node;
            this.cost = cost;
        }
    }
}

