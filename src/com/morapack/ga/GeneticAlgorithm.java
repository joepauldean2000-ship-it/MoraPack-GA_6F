package com.morapack.ga;

import com.morapack.ga.core.PlanLogger;
import com.morapack.ga.core.PlanningState;
import com.morapack.ga.core.SimClock;
import java.io.*;
import java.util.*;

public class GeneticAlgorithm {
    int populationSize = 30;
    double crossoverRate = 0.8;
    double mutationRate = 0.2;
    int maxGenerations = 50;
    int routeLength = 5;

    private final PlanningState planningState;
    private final SimClock clock;
    private final PlanLogger logger;

    public GeneticAlgorithm(PlanningState planningState, SimClock clock, PlanLogger logger) {
        this.planningState = planningState;
        this.clock = clock;
        this.logger = logger;
    }

    private static String flightKey(Vuelo v) {
        return String.valueOf(v.id);
    }

    public void run(List<Vuelo> vuelosDisponibles, List<Pedido> pedidos,
                    Map<String, Aeropuerto> aeropuertos) {

        Map<String, Integer> capRest = planningState.flightCapacity();
        capRest.clear();
        for (Vuelo v : vuelosDisponibles) {
            capRest.put(flightKey(v), v.capacidad);
        }

        int totalSolicitados = 0, totalAsignados = 0, totalPendientes = 0;

        try (PrintWriter writer = new PrintWriter("plan_asignacion_GA.csv")) {
            writer.println("pedido_id,dia,hub_origen,destino,ruta,asignados,pendientes,fitness");

            logger.logMetric("startup", 1.0, clock.now());

            for (Pedido p : pedidos) {
                totalSolicitados += p.cantidad;
                int restantes = p.cantidad;
                int asignadosTotal = 0;

                while (restantes > 0) {
                    Population pop = new Population(populationSize, planningState);
                    pop.initialize(vuelosDisponibles, routeLength, p, aeropuertos);

                    Chromosome best = pop.getBestChromosome();

                    // cuello de botella
                    int cuello = Integer.MAX_VALUE;
                    for (Vuelo v : best.route) {
                        cuello = Math.min(cuello, capRest.getOrDefault(flightKey(v), v.capacidad));
                    }
                    if (cuello <= 0) break;

                    int asignados = Math.min(restantes, cuello);
                    restantes -= asignados;
                    asignadosTotal += asignados;

                    // actualizar capacidades de vuelos
                    for (Vuelo v : best.route) {
                        String key = flightKey(v);
                        capRest.put(key, capRest.get(key) - asignados);
                    }

// ✅ nuevo: ocupar almacén destino por 2h
                    Aeropuerto apDest = aeropuertos.get(p.destino);
                    if (apDest != null) {
                        // calcular minuto de llegada absoluta
                        int minutoLlegada = p.dia * 24 * 60 + p.hora * 60 + p.minuto;
                        for (Vuelo v : best.route) {
                            minutoLlegada += (int)(v.horasDuracion * 60);
                        }
                        // ocupar 2h = 120 minutos
                        clock.advanceTo(minutoLlegada);
                        java.util.NavigableMap<Long, Integer> timeline = planningState.warehouseTimeline(p.destino);
                        for (long m = minutoLlegada; m < minutoLlegada + 120; m++) {
                            timeline.put(m, timeline.getOrDefault(m, 0) + asignados);
                        }
                    }


                    writer.printf("%s,%d,%s,%s,\"%s\",%d,%d,%.2f%n",
                            p.id, p.dia, p.hubOrigen, p.destino,
                            best.route.toString(), asignados, restantes, best.fitness);

                    logger.logMetric("pedido_asignado", asignados, clock.now());

                    if (restantes == 0) break;
                }

                totalAsignados += asignadosTotal;
                totalPendientes += restantes;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Resumen en consola
        System.out.println("\n=== Resumen GA ===");
        System.out.println("Pedidos totales: " + pedidos.size());
        System.out.println("Paquetes solicitados: " + totalSolicitados);
        System.out.println("Paquetes asignados: " + totalAsignados);
        System.out.println("Paquetes pendientes: " + totalPendientes);
    }
}