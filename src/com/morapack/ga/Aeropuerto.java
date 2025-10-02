package com.morapack.ga;
import java.util.TreeMap;

public class Aeropuerto {
    public int id;
    public String codigo;
    public int capacidad;
    public String continente;
    public final double latitud;
    public final double longitud;
    public TreeMap<Integer, Integer> ocupacionPorMinuto = new TreeMap<>();

    public Aeropuerto(int id, String codigo, int capacidad, String continente,
                      double latitud, double longitud) {
        this.id = id;
        this.codigo = codigo;
        this.capacidad = capacidad;
        this.continente = continente;
        this.latitud = latitud;
        this.longitud = longitud;
    }
}
