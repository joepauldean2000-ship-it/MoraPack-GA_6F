package com.morapack.ga;

import com.morapack.ga.core.PlanningState;
import java.util.*;

public final class RoutesRepairer {
    private RoutesRepairer() {
    }

    public static Chromosome repair(Chromosome chromosome) {
        if (chromosome == null) {
            return null;
        }
        List<Vuelo> genes = new ArrayList<>(chromosome.getRoute());
        if (genes.isEmpty()) {
            chromosome.setStructurallyFeasible(false);
            return chromosome;
        }

        genes.sort(Comparator.comparingInt(v -> v.salidaMin));

        List<Vuelo> bestChain = Collections.emptyList();
        for (int i = 0; i < genes.size(); i++) {
            List<Vuelo> candidateChain = buildChainFromIndex(genes, i);
            if (candidateChain.size() > bestChain.size()) {
                bestChain = candidateChain;
            }
        }

        if (bestChain.isEmpty() || !checkConnectivityAndTime(bestChain)) {
            chromosome.setRoute(Collections.emptyList());
            chromosome.setStructurallyFeasible(false);
            return chromosome;
        }

        chromosome.setRoute(bestChain);
        chromosome.setStructurallyFeasible(true);
        return chromosome;
    }

    private static List<Vuelo> buildChainFromIndex(List<Vuelo> sortedGenes, int startIndex) {
        List<Vuelo> chain = new ArrayList<>();
        if (sortedGenes.isEmpty() || startIndex < 0 || startIndex >= sortedGenes.size()) {
            return chain;
        }

        Vuelo start = sortedGenes.get(startIndex);
        chain.add(start);
        Set<String> visited = new HashSet<>();
        visited.add(start.origen);
        visited.add(start.destino);

        String currentTo = start.destino;
        int currentArrival = start.llegadaMin;

        List<Vuelo> pool = new ArrayList<>();
        for (int i = 0; i < sortedGenes.size(); i++) {
            if (i != startIndex) {
                pool.add(sortedGenes.get(i));
            }
        }

        boolean extended = true;
        while (extended) {
            extended = false;
            Vuelo connector = null;
            int connectorIndex = -1;
            for (int i = 0; i < pool.size(); i++) {
                Vuelo candidate = pool.get(i);
                if (!candidate.origen.equals(currentTo)) {
                    continue;
                }
                if (candidate.salidaMin < currentArrival) {
                    continue;
                }
                if (visited.contains(candidate.destino)) {
                    continue;
                }
                connector = candidate;
                connectorIndex = i;
                break;
            }
            if (connector != null) {
                chain.add(connector);
                visited.add(connector.destino);
                currentTo = connector.destino;
                currentArrival = connector.llegadaMin;
                pool.remove(connectorIndex);
                extended = true;
            }
        }

        return chain;
    }

    private static boolean checkConnectivityAndTime(List<Vuelo> route) {
        if (route.isEmpty()) {
            return false;
        }
        for (int i = 0; i < route.size() - 1; i++) {
            Vuelo current = route.get(i);
            Vuelo next = route.get(i + 1);
            if (!current.destino.equals(next.origen)) {
                return false;
            }
            if (current.llegadaMin > next.salidaMin) {
                return false;
            }
        }
        return true;
    }

    public static boolean isFeasible(Chromosome chromosome, PlanningState planningState) {
        if (chromosome == null) {
            return false;
        }
        List<Vuelo> genes = chromosome.getRoute();
        if (genes == null || genes.isEmpty()) {
            return false;
        }

        Set<String> seen = new HashSet<>();
        Vuelo first = genes.get(0);
        seen.add(first.origen);
        for (int i = 0; i < genes.size(); i++) {
            Vuelo current = genes.get(i);
            if (i > 0) {
                Vuelo prev = genes.get(i - 1);
                if (!prev.destino.equals(current.origen)) {
                    return false;
                }
                if (prev.llegadaMin > current.salidaMin) {
                    return false;
                }
            }
            if (seen.contains(current.destino)) {
                return false;
            }
            seen.add(current.destino);
        }

        // Structural repair ensures connectivity and time ordering; capacity checks remain in the
        // fitness evaluation so planningState is not consulted here.
        return true;
    }
}
