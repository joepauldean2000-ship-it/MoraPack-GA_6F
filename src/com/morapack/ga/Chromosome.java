package com.morapack.ga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
