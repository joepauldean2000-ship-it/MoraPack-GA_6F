package com.morapack.ga;

import com.morapack.ga.core.CsvPlanLogger;
import com.morapack.ga.core.EventDrivenClock;
import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Main {
    private static int tsMin(Pedido pedido) {
        return pedido.dia * 1440 + pedido.hora * 60 + pedido.minuto;
    }

    private static List<List<Pedido>> makeBatches(List<Pedido> pedidos, GAParams params) {
        if (pedidos == null || pedidos.isEmpty()) {
            return Collections.emptyList();
        }
        if (params == null || !params.batchingEnabled || params.batchWindowMin <= 0) {
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
            if (delta <= params.batchWindowMin) {
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

    public static void main(String[] args) {
        DataLoader.loadAeropuertos("data/aeropuertos.txt");
        DataLoader.loadVuelos("data/vuelos.txt");
        DataLoader.loadPedidos("data/pedidos.txt");

        SimClock clock = new EventDrivenClock(0L);
        PlanningState planningState = new PlanningState();
        BusinessRules businessRules = new BusinessRules();
        GAParams gaParams = new GAParams(160, 200, 2, 0.9, 0.06, 0.02);
        List<List<Pedido>> batches = makeBatches(DataLoader.pedidos, gaParams);

        try (CsvPlanLogger csvLogger = new CsvPlanLogger(Paths.get("out"));
             GaMetricsCsv gaMetricsCsv = new GaMetricsCsv(Paths.get("out/metrics_ga.csv"));
             PrintWriter planWriter = new PrintWriter("plan_asignacion_GA.csv")) {
            PlanLogger logger = csvLogger;
            GeneticAlgorithm ga = new GeneticAlgorithm(planningState, clock, logger);
            ga.setupExecution(DataLoader.vuelos, DataLoader.aeropuertos, gaParams, businessRules, gaMetricsCsv, planWriter);
            int batchIndex = 0;
            for (List<Pedido> batch : batches) {
                if (batch.isEmpty()) {
                    continue;
                }
                System.out.printf("=== Batch %d (%d pedidos) ===%n", ++batchIndex, batch.size());
                ga.runGAForBatch(batch, planningState, businessRules, gaParams);
            }
            ga.finalizeExecution();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
