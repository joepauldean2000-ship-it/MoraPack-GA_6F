package com.morapack.planificador.dominio;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility helpers for geographic calculations such as DMS parsing and Haversine distances.
 */
public final class GeoUtils {
    private static final Pattern DMS_PATTERN = Pattern.compile(
            "(?i)\\s*([0-9]{1,3})[°º]\\s*([0-9]{1,2})['’]?\\s*([0-9]{1,2})(?:\"|”)?\\s*([NSEW])?\\s*");
    private static final double EARTH_RADIUS_KM = 6_371.0088;

    private GeoUtils() {
    }

    /**
     * Parses a DMS (degrees/minutes/seconds) coordinate string into decimal degrees.
     * Supports formats such as {@code 12°01'19"S}. When the input already contains a decimal
     * separator it is parsed directly as decimal degrees.
     */
    public static double parseDms(String dms) {
        if (dms == null || dms.isBlank()) {
            return 0.0;
        }
        String trimmed = dms.trim();
        if (trimmed.matches("[-+]?\\d+(?:\\.\\d+)?")) {
            return Double.parseDouble(trimmed);
        }
        Matcher matcher = DMS_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Formato DMS inválido: " + dms);
        }
        int degrees = Integer.parseInt(matcher.group(1));
        int minutes = Integer.parseInt(matcher.group(2));
        int seconds = Integer.parseInt(matcher.group(3));
        double decimal = degrees + minutes / 60.0 + seconds / 3600.0;
        String hemi = matcher.group(4);
        if (hemi != null) {
            hemi = hemi.toUpperCase(Locale.ROOT);
            if ("SW".contains(hemi)) {
                decimal = -decimal;
            }
        }
        return decimal;
    }

    /**
     * Computes the great-circle distance between two points in decimal degrees.
     */
    public static double haversine(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lon1 = Math.toRadians(lon1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double lon2 = Math.toRadians(lon2Deg);
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_KM * c;
    }
}
