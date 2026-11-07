package com.andrew.insighthud;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses header-free ELM327 responses. Missing or malformed readings stay unavailable. */
final class ObdProtocol {
    private static final Pattern VOLTAGE = Pattern.compile("(?m)^\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*V\\s*$");

    static byte[] payload(String response, int pid, int count) {
        String prefix = String.format(Locale.US, "41%02X", pid);
        for (String line : response.toUpperCase(Locale.US).split("[\\r\\n]+")) {
            String hex = line.replaceAll("\\s", "");
            if (!hex.matches("[0-9A-F]+") || !hex.startsWith(prefix)
                    || hex.length() < 4 + count * 2 || hex.length() % 2 != 0) continue;
            byte[] result = new byte[count];
            for (int index = 0; index < count; index++) {
                result[index] = (byte) Integer.parseInt(hex.substring(4 + index * 2, 6 + index * 2), 16);
            }
            return result;
        }
        return null;
    }

    static boolean supports(byte[] bitmap, int pid) {
        if (bitmap == null || bitmap.length != 4 || pid < 1 || pid > 32) return false;
        int offset = pid - 1;
        return ((bitmap[offset / 8] & 0xFF) & (0x80 >> (offset % 8))) != 0;
    }

    static Double temperatureF(String response, int pid) {
        byte[] bytes = payload(response, pid, 1);
        return bytes == null ? null : ((bytes[0] & 0xFF) - 40) * 1.8 + 32;
    }

    static Double engineLoad(String response) {
        byte[] bytes = payload(response, 4, 1);
        return bytes == null ? null : (bytes[0] & 0xFF) * 100.0 / 255;
    }

    static Double speedMph(String response) {
        byte[] bytes = payload(response, 0x0D, 1);
        return bytes == null ? null : (bytes[0] & 0xFF) / 1.609344;
    }

    static Double engineRpm(String response) {
        byte[] bytes = payload(response, 0x0C, 2);
        return bytes == null ? null : ((bytes[0] & 0xFF) * 256 + (bytes[1] & 0xFF)) / 4.0;
    }

    static Double throttle(String response) {
        byte[] bytes = payload(response, 0x11, 1);
        return bytes == null ? null : (bytes[0] & 0xFF) * 100.0 / 255;
    }

    // Fuel use is estimated from calculated engine load (normalized air mass), RPM and the Insight's
    // 0.995 L displacement, assuming a stoichiometric mixture. The real engine runs lean at cruise, so
    // treat the result as relative feedback, not an exact figure.
    private static final double DISPLACEMENT_LITERS = 0.995;
    private static final double AIR_GRAMS_PER_LITER = 1.184;
    private static final double STOICH_AIR_FUEL = 14.7;
    private static final double FUEL_GRAMS_PER_LITER = 740;
    private static final double LITERS_PER_GALLON = 3.785;

    static Double fuelGallonsPerHour(Double load, Double rpm) {
        if (load == null || rpm == null || rpm < 400) return null;
        double airGramsPerSecond = load / 100 * DISPLACEMENT_LITERS * rpm / 120 * AIR_GRAMS_PER_LITER;
        double fuelGramsPerSecond = airGramsPerSecond / STOICH_AIR_FUEL;
        return fuelGramsPerSecond * 3600 / FUEL_GRAMS_PER_LITER / LITERS_PER_GALLON;
    }

    static Double estimatedMpg(Double speedMph, Double gallonsPerHour) {
        if (speedMph == null || gallonsPerHour == null || speedMph < 1 || gallonsPerHour < 0.05) return null;
        return Math.min(99, speedMph / gallonsPerHour);
    }

    static Double voltage(String response) {
        Matcher match = VOLTAGE.matcher(response.toUpperCase(Locale.US));
        if (!match.find()) return null;
        double volts = Double.parseDouble(match.group(1));
        return volts <= 30 ? volts : null;
    }
}
