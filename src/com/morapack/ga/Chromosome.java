package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class Chromosome {
    List<Vuelo> route;
    double fitness = Double.NaN;
    private boolean structurallyFeasible = true;
    private Pedido pedido;
    private int departureMinute = -1;
    private int arrivalMinute = -1;

    public Chromosome(List<Vuelo> route) {
        this.route = new ArrayList<>(route);
    }

    public static Chromosome ofRoute(Pedido pedido, List<Vuelo> route) {
        if (pedido == null || route == null || route.isEmpty()) {
            return null;
        }
        Chromosome chromosome = new Chromosome(route);
        chromosome.setPedido(pedido);
        return chromosome;
    }

    public static Chromosome randomInit(List<Pedido> pedidos, GraphVuelos graph,
                                        PlanningState state, Random random) {
        if (pedidos == null || pedidos.isEmpty() || graph == null || random == null) {
            return null;
        }
        int attempts = Math.max(10, pedidos.size() * 4);
        for (int attempt = 0; attempt < attempts; attempt++) {
            Pedido pedido = pedidos.get(random.nextInt(pedidos.size()));
            Chromosome candidate = randomForPedido(pedido, graph, state, random);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Chromosome randomForPedido(Pedido pedido, GraphVuelos graph,
                                              PlanningState state, Random random) {
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
                Chromosome chromosome = new Chromosome(path);
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

    public List<Vuelo> getRoute() {
        return route;
    }

    public void setRoute(List<Vuelo> route) {
        this.route = new ArrayList<>(route);
    }

    public boolean isStructurallyFeasible() {
        return structurallyFeasible && route != null && !route.isEmpty();
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

    @Override
    public String toString() {
        List<Vuelo> r = route == null ? Collections.emptyList() : route;
        return "Ruta=" + r + " | Fitness=" + (Double.isNaN(fitness) ? "NaN" : String.format("%.2f", fitness));
    }
}
