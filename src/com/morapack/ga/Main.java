package com.morapack.ga;

import com.morapack.ga.core.CsvPlanLogger;
import com.morapack.ga.core.EventDrivenClock;
import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import com.morapack.io.ReplanEventLogger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.Set;

public class Main {
    private static final EnumSet<Trigger> pendingTriggers = EnumSet.noneOf(Trigger.class);
    private static final Map<String, Integer> baseFlightCapacity = new HashMap<>();
    private static final List<Pedido> cancellationsQueue = new ArrayList<>();
    private static final PriorityQueue<CancellationEvent> cancellationSchedule =
            new PriorityQueue<>();
    private static final Set<String> cancellationSeen = new HashSet<>();
    private static final Set<String> cancellationAppliedKeys = new HashSet<>();
    private static final Map<String, Pedido> pedidosById = new HashMap<>();
    private static final CancellationDelta cancellationDelta = new CancellationDelta();
    private static GAParams paramsRef;
    private static SimClock clockRef;
    private static PlanningState planningStateRef;
    private static BusinessRules rulesRef;
    private static GeneticAlgorithm gaRef;
    private static ReplanEventLogger replanEventLogger;
    private static long pendingTriggerTime = Long.MIN_VALUE;
    private static long lastReplanTime = Long.MIN_VALUE;
    private static long lastFreezeBoundary = Long.MIN_VALUE;
    private static long nextTimeTrigger = Long.MIN_VALUE;

    private static int tsMin(Pedido pedido) {
        return pedido.dia * 1440 + pedido.hora * 60 + pedido.minuto;
    }

    private static List<List<Pedido>> makeBatches(List<Pedido> pedidos, long windowMin) {
        if (pedidos == null || pedidos.isEmpty()) {
            return Collections.emptyList();
        }
        if (paramsRef == null || !paramsRef.batchingEnabled || windowMin <= 0) {
            List<List<Pedido>> single = new ArrayList<>();
            single.add(new ArrayList<>(pedidos));
            return single;
        }
        List<Pedido> sorted = new ArrayList<>(pedidos);
        sorted.sort(Comparator.comparingInt(Main::tsMin));
        List<List<Pedido>> batches = new ArrayList<>();
        List<Pedido> current = new ArrayList<>();
        int startTs = -1;
        for (Pedido pedido : sorted) {
            int ts = tsMin(pedido);
            if (current.isEmpty()) {
                current.add(pedido);
                startTs = ts;
                continue;
            }
            long delta = (long) ts - startTs;
            if (delta <= windowMin) {
                current.add(pedido);
            } else {
                batches.add(new ArrayList<>(current));
                current = new ArrayList<>();
                current.add(pedido);
                startTs = ts;
            }
        }
        if (!current.isEmpty()) {
            batches.add(new ArrayList<>(current));
        }
        return batches;
    }

    private static void loadCancellationSchedule(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(path))) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 2) {
                    System.err.printf("[Cancelaciones] Línea %d inválida: %s%n", lineNumber, line);
                    continue;
                }
                String tsRaw = parts[0].trim();
                String pedidoId = parts[1].trim();
                if (tsRaw.isEmpty() || pedidoId.isEmpty()) {
                    System.err.printf("[Cancelaciones] Falta timestamp o pedido en línea %d%n", lineNumber);
                    continue;
                }
                long timestamp;
                try {
                    timestamp = Long.parseLong(tsRaw);
                } catch (NumberFormatException ex) {
                    System.err.printf("[Cancelaciones] Timestamp inválido en línea %d: %s%n", lineNumber, tsRaw);
                    continue;
                }
                String motivo = parts.length > 2 ? parts[2].trim() : "";
                String fuente = parts.length > 3 ? parts[3].trim() : "archivo";
                CancellationEvent event = new CancellationEvent(timestamp, pedidoId, motivo, fuente);
                String key = event.key();
                if (!cancellationSeen.add(key)) {
                    continue;
                }
                cancellationSchedule.add(event);
            }
            System.out.printf("[Cancelaciones] Eventos cargados: %d desde %s%n",
                    cancellationSchedule.size(), path);
        } catch (IOException ex) {
            System.err.printf("[Cancelaciones] Error leyendo %s: %s%n", path, ex.getMessage());
        }
    }

    private static void advanceTime(long target, List<Pedido> backlog, BusinessRules rules, GAParams params) {
        if (clockRef == null) {
            return;
        }
        long now = clockRef.now();
        if (target < now) {
            return;
        }
        if (params != null && params.replanEnabled && params.replanEveryMin > 0) {
            if (nextTimeTrigger == Long.MIN_VALUE || nextTimeTrigger == 0L) {
                nextTimeTrigger = now + params.replanEveryMin;
            }
            while (nextTimeTrigger != Long.MAX_VALUE && nextTimeTrigger <= target) {
                scheduleReplan(Trigger.TIME, nextTimeTrigger);
                replanIfNeeded(nextTimeTrigger, planningStateRef, backlog, rules, params);
                nextTimeTrigger += params.replanEveryMin;
            }
        }
        processCancellationTriggers(target, backlog, rules, params);
        clockRef.advanceTo(target);
    }

    private static void processCancellationTriggers(long target, List<Pedido> backlog,
                                                    BusinessRules rules, GAParams params) {
        if (cancellationSchedule.isEmpty() || params == null || !params.replanEnabled) {
            return;
        }
        while (!cancellationSchedule.isEmpty()) {
            CancellationEvent next = cancellationSchedule.peek();
            if (next == null || next.timestampSim > target) {
                break;
            }
            String keyBefore = next.key();
            scheduleReplan(Trigger.CANCELLED_ORDERS, next.timestampSim);
            long replanTime = next.timestampSim;
            replanIfNeeded(replanTime, planningStateRef, backlog, rules, params);
            if (cancellationSchedule.isEmpty()) {
                break;
            }
            CancellationEvent head = cancellationSchedule.peek();
            if (head == null || head.key().equals(keyBefore)) {
                break;
            }
        }
    }

    private static void backlogAdd(List<Pedido> backlog, Pedido pedido) {
        if (pedido == null || backlog == null) {
            return;
        }
        if (!backlog.contains(pedido)) {
            backlog.add(pedido);
        }
    }

    public static void scheduleReplan(Trigger trigger, long tSim) {
        if (trigger == null || paramsRef == null || !paramsRef.replanEnabled) {
            return;
        }
        if (lastReplanTime != Long.MIN_VALUE && tSim - lastReplanTime < paramsRef.cooldownMin) {
            return;
        }
        if (pendingTriggerTime == Long.MIN_VALUE || tSim < pendingTriggerTime) {
            pendingTriggerTime = tSim;
        }
        pendingTriggers.add(trigger);
    }

    public static void replanIfNeeded(long tNow, PlanningState st, List<Pedido> backlog,
                                      BusinessRules br, GAParams params) {
        if (!shouldReplan(tNow, params)) {
            return;
        }
        cancellationDelta.resetEvents();
        drainDueCancellations(tNow);
        if (gaRef == null) {
            pendingTriggers.clear();
            pendingTriggerTime = Long.MIN_VALUE;
            lastReplanTime = tNow;
            return;
        }
        freezeCommitted(st, tNow, params.freezeHorizonMin);
        cancellationDelta.resetApplied();
        applyCancellations(st, br, backlog);

        List<Pedido> toPlan = collectBacklog(backlog, tNow, params);
        if (toPlan.isEmpty()) {
            pendingTriggers.clear();
            pendingTriggerTime = Long.MIN_VALUE;
            lastReplanTime = tNow;
            return;
        }

        toPlan.sort((a, b) -> {
            int cmp = Integer.compare(priorityValue(a.priority()), priorityValue(b.priority()));
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(a.getCreatedTimestamp(), b.getCreatedTimestamp());
        });

        ReplanDelta delta = releaseAssignments(toPlan, lastFreezeBoundary, st, br);

        List<List<Pedido>> batches = makeBatches(toPlan, params.batchWindowMin);
        int batchIndex = 0;
        for (List<Pedido> batch : batches) {
            if (batch.isEmpty()) {
                continue;
            }
            System.out.printf("=== REPLAN @ t=%d (batch %d, %d pedidos) ===%n", tNow, ++batchIndex, batch.size());
            List<Pedido> urgents = new ArrayList<>();
            List<Pedido> rest = new ArrayList<>();
            for (Pedido pedido : batch) {
                if (pedido.isUrgent()) {
                    urgents.add(pedido);
                } else {
                    rest.add(pedido);
                }
            }
            if (!urgents.isEmpty()) {
                gaRef.runGAForBatch(urgents, st, br, params);
            }
            if (!rest.isEmpty()) {
                gaRef.runGAForBatch(rest, st, br, params);
            }
            pruneBacklog(backlog);
        }

        logReplanKPIs(tNow, st, br, delta, backlog);
        pendingTriggers.clear();
        pendingTriggerTime = Long.MIN_VALUE;
        lastReplanTime = tNow;
        detectCapacityStress(tNow, st, params);
    }

    private static void drainDueCancellations(long tNow) {
        boolean drainedAny = false;
        while (!cancellationSchedule.isEmpty()) {
            CancellationEvent next = cancellationSchedule.peek();
            if (next == null || next.timestampSim > tNow) {
                break;
            }
            cancellationSchedule.poll();
            String key = next.key();
            if (!cancellationAppliedKeys.add(key)) {
                continue;
            }
            Pedido pedido = pedidosById.get(next.pedidoId);
            if (pedido == null) {
                System.err.printf("[Cancelaciones] Pedido %s inexistente; se ignora evento en t=%d%n",
                        next.pedidoId, next.timestampSim);
                continue;
            }
            if (pedido.isCancelled()) {
                continue;
            }
            if (!cancellationsQueue.contains(pedido)) {
                cancellationsQueue.add(pedido);
            }
            drainedAny = true;
            cancellationDelta.drainedEvents++;
        }
        if (drainedAny && !pendingTriggers.contains(Trigger.CANCELLED_ORDERS)) {
            scheduleReplan(Trigger.CANCELLED_ORDERS, tNow);
        }
    }

    private static boolean shouldReplan(long tNow, GAParams params) {
        if (params == null || !params.replanEnabled) {
            pendingTriggers.clear();
            pendingTriggerTime = Long.MIN_VALUE;
            return false;
        }
        if (pendingTriggers.isEmpty()) {
            return false;
        }
        if (lastReplanTime != Long.MIN_VALUE && tNow - lastReplanTime < params.cooldownMin) {
            return false;
        }
        if (pendingTriggerTime != Long.MIN_VALUE && tNow < pendingTriggerTime) {
            return false;
        }
        return true;
    }

    private static void freezeCommitted(PlanningState st, long tNow, long freezeHorizonMin) {
        lastFreezeBoundary = tNow + Math.max(0, freezeHorizonMin);
    }

    private static List<Pedido> collectBacklog(List<Pedido> backlog, long tNow, GAParams params) {
        List<Pedido> candidates = new ArrayList<>();
        if (backlog == null) {
            return candidates;
        }
        long horizon = params != null ? Math.max(0, params.freezeHorizonMin) : 0L;
        Iterator<Pedido> it = backlog.iterator();
        while (it.hasNext()) {
            Pedido pedido = it.next();
            if (pedido == null || pedido.isCancelled()) {
                it.remove();
                continue;
            }
            if (pedido.getRemainingQuantity() <= 0) {
                it.remove();
                continue;
            }
            if (pedido.isFrozen(tNow, horizon)) {
                continue;
            }
            candidates.add(pedido);
        }
        return candidates;
    }

    private static ReplanDelta releaseAssignments(List<Pedido> pedidos, long freezeBoundary,
                                                  PlanningState st, BusinessRules rules) {
        ReplanDelta delta = new ReplanDelta();
        if (pedidos == null) {
            return delta;
        }
        for (Pedido pedido : pedidos) {
            List<Pedido.AssignmentRecord> released = pedido.detachAssignmentsAfter(freezeBoundary);
            if (released.isEmpty()) {
                continue;
            }
            delta.ordersTouched++;
            for (Pedido.AssignmentRecord record : released) {
                delta.freedPackages += record.quantity();
                restoreAssignment(record, st, rules);
            }
        }
        return delta;
    }

    private static void restoreAssignment(Pedido.AssignmentRecord record, PlanningState st, BusinessRules rules) {
        if (record == null || st == null) {
            return;
        }
        List<Vuelo> route = record.route();
        if (route == null || route.isEmpty()) {
            return;
        }
        int quantity = record.quantity();
        for (Vuelo vuelo : route) {
            if (vuelo == null) {
                continue;
            }
            String key = String.valueOf(vuelo.id);
            int base = baseFlightCapacity.getOrDefault(key, vuelo.capacidad);
            int current = st.flightCapacity().getOrDefault(key, 0);
            int updated = Math.min(base, current + quantity);
            st.flightCapacity().put(key, updated);
        }
        int arrival = record.arrival();
        if (arrival >= 0 && rules != null) {
            String destino = record.destination();
            if (destino != null) {
                NavigableMap<Long, Integer> timeline = st.warehouseTimeline(destino);
                for (long minute = arrival; minute < arrival + rules.whBlockMin; minute++) {
                    int value = timeline.getOrDefault(minute, 0) - quantity;
                    if (value <= 0) {
                        timeline.remove(minute);
                    } else {
                        timeline.put(minute, value);
                    }
                }
            }
        }
    }

    private static void applyCancellations(PlanningState st, BusinessRules rules, List<Pedido> backlog) {
        if (cancellationsQueue.isEmpty()) {
            cancellationDelta.resetApplied();
            return;
        }
        cancellationDelta.resetApplied();
        Iterator<Pedido> it = cancellationsQueue.iterator();
        while (it.hasNext()) {
            Pedido pedido = it.next();
            if (pedido == null) {
                it.remove();
                continue;
            }
            if (pedido.isCancelled()) {
                it.remove();
                continue;
            }
            List<Pedido.AssignmentRecord> snapshot = new ArrayList<>(pedido.assignments());
            int freedForPedido = 0;
            for (Pedido.AssignmentRecord record : snapshot) {
                restoreAssignment(record, st, rules);
                freedForPedido += record.quantity();
            }
            pedido.cancel();
            cancellationDelta.cancelledOrders++;
            cancellationDelta.freedPackages += freedForPedido;
            it.remove();
        }
        pruneBacklog(backlog);
    }

    private static void pruneBacklog(List<Pedido> backlog) {
        if (backlog == null) {
            return;
        }
        Iterator<Pedido> it = backlog.iterator();
        while (it.hasNext()) {
            Pedido pedido = it.next();
            if (pedido == null || pedido.isCancelled() || pedido.getRemainingQuantity() <= 0) {
                it.remove();
            }
        }
    }

    private static void logReplanKPIs(long tNow, PlanningState st, BusinessRules rules,
                                      ReplanDelta delta, List<Pedido> backlog) {
        int urgentTotal = 0;
        int urgentOnTime = 0;
        for (Pedido pedido : DataLoader.pedidos) {
            if (pedido == null || !pedido.isUrgent()) {
                continue;
            }
            urgentTotal++;
            if (pedido.isCompleted() && pedido.meetsSla(rules, DataLoader.aeropuertos)) {
                urgentOnTime++;
            }
        }
        double urgentSla = urgentTotal > 0 ? (urgentOnTime * 100.0) / urgentTotal : 100.0;
        int backlogSize = backlog != null ? backlog.size() : 0;
        int totalFreed = delta.freedPackages + cancellationDelta.freedPackages;
        System.out.printf("[Replan KPI] t=%d freed=%d (cancel=%d) moved=%d urgentSLA=%.1f%% backlog=%d%n",
                tNow, totalFreed, cancellationDelta.freedPackages, delta.ordersTouched,
                urgentSla, backlogSize);
        Trigger triggerLogged = null;
        if (pendingTriggers.contains(Trigger.CANCELLED_ORDERS)) {
            triggerLogged = Trigger.CANCELLED_ORDERS;
        } else if (!pendingTriggers.isEmpty()) {
            triggerLogged = pendingTriggers.iterator().next();
        }
        if (replanEventLogger != null) {
            replanEventLogger.log(tNow, triggerLogged, 0,
                    cancellationDelta.cancelledOrders, cancellationDelta.freedPackages,
                    delta.ordersTouched, backlogSize, 0);
        }
    }

    private static void detectCapacityStress(long tNow, PlanningState st, GAParams params) {
        if (st == null || params == null || !params.replanEnabled) {
            return;
        }
        double threshold = params.capCriticalThreshold;
        if (threshold <= 0) {
            return;
        }
        for (Map.Entry<String, Integer> entry : st.flightCapacity().entrySet()) {
            int base = baseFlightCapacity.getOrDefault(entry.getKey(), 0);
            if (base <= 0) {
                continue;
            }
            double usedRatio = 1.0 - (entry.getValue() / (double) base);
            if (usedRatio >= threshold) {
                scheduleReplan(Trigger.CAP_CRITICAL, tNow);
                break;
            }
        }
    }

    private static int priorityValue(GAParams.Priority priority) {
        if (priority == GAParams.Priority.URGENT) {
            return 0;
        }
        if (priority == GAParams.Priority.EXPRESS) {
            return 1;
        }
        return 2;
    }

    private static final class CancellationDelta {
        int drainedEvents;
        int cancelledOrders;
        int freedPackages;

        void resetEvents() {
            drainedEvents = 0;
        }

        void resetApplied() {
            cancelledOrders = 0;
            freedPackages = 0;
        }
    }

    private static final class ReplanDelta {
        int freedPackages;
        int ordersTouched;
    }

    public static void main(String[] args) {
        String cancellationsPath = null;
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--cancellations=")) {
                    cancellationsPath = arg.substring("--cancellations=".length());
                }
            }
        }
        DataLoader.loadAeropuertos("data/aeropuertos.txt");
        DataLoader.loadVuelos("data/vuelos.txt");
        DataLoader.loadPedidos("data/pedidos.txt");

        SimClock clock = new EventDrivenClock(0L);
        PlanningState planningState = new PlanningState();
        BusinessRules businessRules = new BusinessRules();
        GAParams gaParams = new GAParams(160, 200, 2, 0.9, 0.06, 0.02);

        paramsRef = gaParams;
        clockRef = clock;
        planningStateRef = planningState;
        rulesRef = businessRules;

        for (Pedido pedido : DataLoader.pedidos) {
            if (pedido != null && pedido.id != null) {
                pedidosById.put(pedido.id, pedido);
            }
        }

        for (Vuelo vuelo : DataLoader.vuelos) {
            String key = String.valueOf(vuelo.id);
            baseFlightCapacity.put(key, vuelo.capacidad);
        }

        if (cancellationsPath != null && !cancellationsPath.isBlank()) {
            loadCancellationSchedule(cancellationsPath);
        }

        try (CsvPlanLogger csvLogger = new CsvPlanLogger(Paths.get("out"));
             GaMetricsCsv gaMetricsCsv = new GaMetricsCsv(Paths.get("out/metrics_ga.csv"));
             ReplanEventLogger replanLogger = new ReplanEventLogger(Paths.get("out/replan_events.csv"));
             PrintWriter planWriter = new PrintWriter("plan_asignacion_GA.csv")) {
            replanLogger.open();
            PlanLogger logger = csvLogger;
            GeneticAlgorithm ga = new GeneticAlgorithm(planningState, clock, logger);
            gaRef = ga;
            ga.setupExecution(DataLoader.vuelos, DataLoader.aeropuertos, gaParams, businessRules, gaMetricsCsv, planWriter);
            replanEventLogger = replanLogger;

            List<Pedido> backlog = new ArrayList<>();
            List<Pedido> ordered = new ArrayList<>(DataLoader.pedidos);
            ordered.sort(Comparator.comparingInt(Main::tsMin));

            if (!ordered.isEmpty()) {
                clock.advanceTo(tsMin(ordered.get(0)));
            }
            if (gaParams.replanEnabled && gaParams.replanEveryMin > 0) {
                nextTimeTrigger = clock.now() + gaParams.replanEveryMin;
            } else {
                nextTimeTrigger = Long.MAX_VALUE;
            }

            for (Pedido pedido : ordered) {
                long eventTime = tsMin(pedido);
                advanceTime(eventTime, backlog, businessRules, gaParams);
                backlogAdd(backlog, pedido);
                if (pedido.isUrgent()) {
                    scheduleReplan(Trigger.URGENT_ORDER, clock.now());
                }
                scheduleReplan(Trigger.NEW_ORDERS, clock.now());
                replanIfNeeded(clock.now(), planningState, backlog, businessRules, gaParams);
                detectCapacityStress(clock.now(), planningState, gaParams);
                replanIfNeeded(clock.now(), planningState, backlog, businessRules, gaParams);
            }

            scheduleReplan(Trigger.TIME, clock.now());
            replanIfNeeded(clock.now(), planningState, backlog, businessRules, gaParams);

            ga.finalizeExecution();
            replanEventLogger = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
