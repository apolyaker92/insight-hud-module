package com.andrew.insighthud;

final class Polyline {
    private Polyline() { }

    static double[][] decode(String encoded, int precision) {
        double scale = Math.pow(10, precision);
        int length = encoded == null ? 0 : encoded.length();
        double[] lat = new double[length / 2 + 1];
        double[] lon = new double[length / 2 + 1];
        int count = 0, index = 0;
        long latitude = 0, longitude = 0;
        while (index < length) {
            long[] first = next(encoded, index);
            if (first == null) break;
            index = (int) first[1];
            long[] second = next(encoded, index);
            if (second == null) break;
            index = (int) second[1];
            latitude += first[0];
            longitude += second[0];
            lat[count] = latitude / scale;
            lon[count] = longitude / scale;
            count++;
        }
        double[] trimmedLat = new double[count];
        double[] trimmedLon = new double[count];
        System.arraycopy(lat, 0, trimmedLat, 0, count);
        System.arraycopy(lon, 0, trimmedLon, 0, count);
        return new double[][]{trimmedLat, trimmedLon};
    }

    private static long[] next(String encoded, int index) {
        long result = 0;
        int shift = 0;
        while (index < encoded.length()) {
            int b = encoded.charAt(index++) - 63;
            if (b < 0 || b > 63) return null;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
            if (b < 0x20) return new long[]{(result & 1) != 0 ? ~(result >> 1) : result >> 1, index};
            if (shift > 60) return null;
        }
        return null;
    }
}
