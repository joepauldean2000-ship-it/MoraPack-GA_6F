package com.morapack.ga;

import com.morapack.ga.core.CsvPlanLogger;
import com.morapack.ga.core.EventDrivenClock;
import com.morapack.ga.core.GaMetricsCsv;
import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) {
        DataLoader.loadAeropuertos("data/aeropuertos.txt");
        DataLoader.loadVuelos("data/vuelos.txt");
        DataLoader.loadPedidos("data/pedidos.txt");

        SimClock clock = new EventDrivenClock(0L);
        PlanningState planningState = new PlanningState();
        BusinessRules businessRules = new BusinessRules();

        try (CsvPlanLogger csvLogger = new CsvPlanLogger(Paths.get("out"));
             GaMetricsCsv gaMetricsCsv = new GaMetricsCsv(Paths.get("out/metrics_ga.csv"))) {
            PlanLogger logger = csvLogger;
            GeneticAlgorithm ga = new GeneticAlgorithm(planningState, clock, logger);
            GAParams gaParams = new GAParams(80, 50, 3, 0.8, 0.2, 0.05);
            ga.run(DataLoader.vuelos, DataLoader.pedidos, DataLoader.aeropuertos, gaParams, gaMetricsCsv, businessRules);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
