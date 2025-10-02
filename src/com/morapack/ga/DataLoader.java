package com.morapack.ga;
import com.morapack.planificador.dominio.GeoUtils;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class DataLoader {
    public static Map<String, Aeropuerto> aeropuertos = new HashMap<>();
    public static List<Vuelo> vuelos = new ArrayList<>();
    public static List<Pedido> pedidos = new ArrayList<>();

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("H:mm");

    // ================================
    // Cargar Aeropuertos
    // ================================
    public static void loadAeropuertos(String path) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(path));
            for (String linea : lines) {
                if (linea == null || linea.isBlank()) continue;

                String[] partes = linea.split(",");
                int id = Integer.parseInt(partes[0].trim());
                String codigo = partes[1].trim();
                int capacidad = Integer.parseInt(partes[6].trim());
                String continente = partes[9].trim();
                double latitud;
                double longitud;
                try {
                    latitud = GeoUtils.parseDms(partes[7]);
                    longitud = GeoUtils.parseDms(partes[8]);
                } catch (IllegalArgumentException ex) {
                    latitud = 0.0;
                    longitud = 0.0;
                    System.err.println("[DataLoader] Coordenadas inválidas para " + codigo + ": " + ex.getMessage());
                }

                Aeropuerto ap = new Aeropuerto(id, codigo, capacidad, continente, latitud, longitud);
                aeropuertos.put(codigo, ap);
            }
            System.out.println("Aeropuertos cargados: " + aeropuertos.size());
        } catch (IOException e) {
            System.err.println("Error cargando aeropuertos: " + e.getMessage());
        }
    }

    // ================================
    // Cargar Vuelos
    // ================================
    public static void loadVuelos(String path) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(path));
            int id = 0;
            for (String linea : lines) {
                if (linea.isBlank()) continue;
                String[] f = linea.split("\\s*,\\s*");
                if (f.length < 5) continue;

                String origen = f[0].trim();
                String destino = f[1].trim();
                if (!aeropuertos.containsKey(origen) || !aeropuertos.containsKey(destino)) continue;

                int salida = hhmmAMinutos(f[2]);
                int llegada = hhmmAMinutos(f[3]);
                int capacidad = parseIntSafe(f[4]);

                // duración ajustada
                int duracion = llegada - salida;
                if (duracion < 0) duracion += 24 * 60;

                // continente origen vs destino
                String contOrigen = aeropuertos.get(origen).continente;
                String contDestino = aeropuertos.get(destino).continente;
                boolean esContinental = contOrigen.equals(contDestino);

                Vuelo v = new Vuelo(id++, origen, destino, salida, llegada, capacidad, duracion, esContinental);
                vuelos.add(v);
            }
            System.out.println("Vuelos cargados: " + vuelos.size());
        } catch (IOException e) {
            System.err.println("Error cargando vuelos: " + e.getMessage());
        }
    }

    // ================================
    // Cargar Pedidos
    // ================================
    public static void loadPedidos(String path) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(path));
            for (String linea : lines) {
                if (linea.trim().isEmpty() || linea.startsWith("#")) continue;

                String[] partes = linea.split("-");
                if (partes.length != 6) continue;

                int dia = Integer.parseInt(partes[0]);
                int hora = Integer.parseInt(partes[1]);
                int minuto = Integer.parseInt(partes[2]);
                String destino = partes[3].trim();
                int cantidad = Integer.parseInt(partes[4]);
                String idCliente = partes[5].trim();

                // Seleccionar hub por región
                String hub = hubParaDestino(destino);

                Pedido p = new Pedido(idCliente, destino, cantidad, hub, dia, hora, minuto);
                pedidos.add(p);
            }
            System.out.println("Pedidos cargados: " + pedidos.size());
        } catch (IOException e) {
            System.err.println("Error cargando pedidos: " + e.getMessage());
        }
    }

    // ================================
    // Helpers
    // ================================
    private static int hhmmAMinutos(String hhmm) {
        LocalTime t = LocalTime.parse(hhmm.trim(), HHMM);
        return t.getHour() * 60 + t.getMinute();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // Definición fija de hubs (como en ACO)
    private static final Map<String, String> HUBS = Map.of(
            "SPIM", "AM",  // Lima
            "EBCI", "EU",  // Bruselas
            "UBBB", "AS"   // Bakú
    );

    private static String hubParaDestino(String destino) {
        Aeropuerto ap = aeropuertos.get(destino);
        if (ap == null) return "EBCI"; // default

        switch (ap.continente) {
            case "AM": return "SPIM";
            case "EU": return "EBCI";
            case "AS": return "UBBB";
            default:   return "EBCI";
        }
    }
}
