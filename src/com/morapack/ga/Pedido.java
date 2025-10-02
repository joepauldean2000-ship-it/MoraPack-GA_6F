package com.morapack.ga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Pedido {
    public String id;
    public String destino;
    public int cantidad;
    public String hubOrigen;
    public int dia;     // día del mes
    public int hora;    // hora de creación del pedido
    public int minuto;  // minuto de creación

    private GAParams.Priority priority = GAParams.Priority.STANDARD;
    private boolean counted;
    private boolean urgentRisk;
    private boolean cancelled;
    private int assigned;
    private int remaining;
    private long createdTimestamp;
    private long lastDeparture = -1;
    private long lastArrival = -1;
    private final List<AssignmentRecord> assignments = new ArrayList<>();

    public Pedido(String id, String destino, int cantidad, String hubOrigen, int dia, int hora, int minuto) {
        this.id = id;
        this.destino = destino;
        this.cantidad = cantidad;
        this.hubOrigen = hubOrigen;
        this.dia = dia;
        this.hora = hora;
        this.minuto = minuto;
        this.remaining = cantidad;
        this.createdTimestamp = dia * 1440L + hora * 60L + minuto;
    }

    public GAParams.Priority priority() {
        return priority;
    }

    public void setPriority(GAParams.Priority priority) {
        if (priority != null) {
            this.priority = priority;
        }
    }

    public boolean isUrgent() {
        return priority == GAParams.Priority.URGENT;
    }

    public void markUrgent() {
        setPriority(GAParams.Priority.URGENT);
    }

    public boolean markProcessedOnce() {
        if (counted) {
            return false;
        }
        counted = true;
        return true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        this.cancelled = true;
        this.remaining = 0;
        this.assigned = 0;
        this.assignments.clear();
        this.lastDeparture = -1;
        this.lastArrival = -1;
        this.urgentRisk = false;
    }

    public int getAssignedQuantity() {
        return assigned;
    }

    public int getRemainingQuantity() {
        return remaining;
    }

    public void addAssignment(List<Vuelo> route, int departure, int arrival, int quantity) {
        if (route == null || route.isEmpty() || quantity <= 0) {
            return;
        }
        assignments.add(new AssignmentRecord(new ArrayList<>(route), quantity, departure, arrival));
        assigned += quantity;
        remaining = Math.max(0, remaining - quantity);
        lastDeparture = departure;
        lastArrival = arrival;
        urgentRisk = false;
    }

    public List<AssignmentRecord> assignments() {
        return Collections.unmodifiableList(assignments);
    }

    public List<AssignmentRecord> detachAssignmentsAfter(long freezeBoundary) {
        if (assignments.isEmpty()) {
            return Collections.emptyList();
        }
        List<AssignmentRecord> released = new ArrayList<>();
        Iterator<AssignmentRecord> it = assignments.iterator();
        while (it.hasNext()) {
            AssignmentRecord record = it.next();
            if (record.departure >= freezeBoundary) {
                released.add(record);
                assigned = Math.max(0, assigned - record.quantity);
                remaining = Math.min(cantidad, remaining + record.quantity);
                it.remove();
            }
        }
        if (assignments.isEmpty()) {
            lastDeparture = -1;
            lastArrival = -1;
        } else {
            AssignmentRecord last = assignments.get(assignments.size() - 1);
            lastDeparture = last.departure;
            lastArrival = last.arrival;
        }
        return released;
    }

    public void setAtRisk(boolean atRisk) {
        this.urgentRisk = atRisk;
    }

    public boolean isAtRisk() {
        return urgentRisk;
    }

    public boolean isCompleted() {
        return remaining <= 0;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public long getLastDeparture() {
        return lastDeparture;
    }

    public long getLastArrival() {
        return lastArrival;
    }

    public boolean isFrozen(long now, long freezeHorizon) {
        if (lastDeparture < 0) {
            return false;
        }
        return lastDeparture < now + freezeHorizon;
    }

    public boolean meetsSla(BusinessRules rules, Map<String, Aeropuerto> aeropuertos) {
        if (rules == null || aeropuertos == null) {
            return false;
        }
        if (lastDeparture < 0 || lastArrival < 0) {
            return false;
        }
        Aeropuerto origen = aeropuertos.get(hubOrigen);
        Aeropuerto destinoAp = aeropuertos.get(destino);
        if (origen == null || destinoAp == null) {
            return false;
        }
        boolean intra = origen.continente != null && origen.continente.equals(destinoAp.continente);
        long sla = (long) (intra ? rules.slaIntraHours : rules.slaInterHours) * 60L;
        long transit = lastArrival - lastDeparture;
        return transit <= sla;
    }

    public static final class AssignmentRecord {
        private final List<Vuelo> route;
        private final int quantity;
        private final int departure;
        private final int arrival;

        AssignmentRecord(List<Vuelo> route, int quantity, int departure, int arrival) {
            this.route = route;
            this.quantity = quantity;
            this.departure = departure;
            this.arrival = arrival;
        }

        public List<Vuelo> route() {
            return route;
        }

        public int quantity() {
            return quantity;
        }

        public int departure() {
            return departure;
        }

        public int arrival() {
            return arrival;
        }

        public String destination() {
            if (route.isEmpty()) {
                return null;
            }
            return route.get(route.size() - 1).destino;
        }
    }
}
