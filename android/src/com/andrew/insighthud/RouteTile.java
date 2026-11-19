package com.andrew.insighthud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

final class RouteTile {
    final long[] id;
    final int[] lat, lon, first;
    final long[] target;
    final int[] targetLat, targetLon;
    final float[] cost, length;
    final byte[] roadClass, flags, bearingStart, bearingEnd;
    final int[] name, shapeFirst, shape;
    final long[] banVia, banBefore, banAfter;
    final byte[] names;

    RouteTile(long[] id, int[] lat, int[] lon, int[] first, long[] target, int[] targetLat, int[] targetLon,
              float[] cost, float[] length, byte[] roadClass, byte[] flags, byte[] bearingStart, byte[] bearingEnd,
              int[] name, int[] shapeFirst, int[] shape, long[] banVia, long[] banBefore, long[] banAfter,
              byte[] names) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.first = first;
        this.target = target;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.cost = cost;
        this.length = length;
        this.roadClass = roadClass;
        this.flags = flags;
        this.bearingStart = bearingStart;
        this.bearingEnd = bearingEnd;
        this.name = name;
        this.shapeFirst = shapeFirst;
        this.shape = shape;
        this.banVia = banVia;
        this.banBefore = banBefore;
        this.banAfter = banAfter;
        this.names = names;
    }

    static RouteTile decode(byte[] blob) throws IOException {
        ByteArrayOutputStream inflated = new ByteArrayOutputStream(blob.length * 4);
        try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(blob))) {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = in.read(buffer)) != -1;) inflated.write(buffer, 0, read);
        }
        ByteBuffer data = ByteBuffer.wrap(inflated.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x32545248) throw new IOException("Not a routing tile");
        int nodes = data.getInt(), edges = data.getInt(), shapePoints = data.getInt();
        int bans = data.getInt(), nameBytes = data.getInt();
        long[] id = longs(data, nodes);
        int[] lat = ints(data, nodes), lon = ints(data, nodes), first = ints(data, nodes + 1);
        long[] target = longs(data, edges);
        int[] targetLat = ints(data, edges), targetLon = ints(data, edges);
        float[] cost = floats(data, edges), length = floats(data, edges);
        byte[] roadClass = bytes(data, edges), flags = bytes(data, edges);
        byte[] bearingStart = bytes(data, edges), bearingEnd = bytes(data, edges);
        int[] name = ints(data, edges), shapeFirst = ints(data, edges + 1), shape = ints(data, 2 * shapePoints);
        long[] banVia = longs(data, bans), banBefore = longs(data, bans), banAfter = longs(data, bans);
        byte[] names = bytes(data, nameBytes);
        return new RouteTile(id, lat, lon, first, target, targetLat, targetLon, cost, length, roadClass, flags,
                bearingStart, bearingEnd, name, shapeFirst, shape, banVia, banBefore, banAfter, names);
    }

    private static long[] longs(ByteBuffer data, int count) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) result[i] = data.getLong();
        return result;
    }

    private static int[] ints(ByteBuffer data, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = data.getInt();
        return result;
    }

    private static float[] floats(ByteBuffer data, int count) {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) result[i] = data.getFloat();
        return result;
    }

    private static byte[] bytes(ByteBuffer data, int count) {
        byte[] result = new byte[count];
        data.get(result);
        return result;
    }

    int nodeCount() { return id.length; }

    int edgeCount() { return target.length; }

    int nodeIndex(long nodeId) {
        int index = Arrays.binarySearch(id, nodeId);
        return index >= 0 ? index : -1;
    }

    int startNodeOf(int edge) {
        int low = 0, high = id.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (first[mid] <= edge) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    String nameOf(int edge) {
        String full = rawName(edge);
        int cut = full.indexOf('');
        return cut < 0 ? full : full.substring(0, cut);
    }

    String[] exitOf(int edge) {
        String full = rawName(edge);
        int cut = full.indexOf('');
        if (cut < 0) return null;
        String[] parts = full.substring(cut + 1).split("", -1);
        String number = parts.length > 0 ? parts[0] : "", toward = parts.length > 1 ? parts[1] : "";
        return number.isEmpty() && toward.isEmpty() ? null : new String[]{number, toward};
    }

    private String rawName(int edge) {
        int offset = name[edge];
        if (offset < 0) return "";
        int end = offset;
        while (end < names.length && names[end] != 0) end++;
        return new String(names, offset, end - offset, StandardCharsets.UTF_8);
    }

    boolean banned(long before, long via, long after) {
        int at = Arrays.binarySearch(banVia, via);
        if (at < 0) return false;
        while (at > 0 && banVia[at - 1] == via) at--;
        for (; at < banVia.length && banVia[at] == via; at++) {
            if (banBefore[at] == before && banAfter[at] == after) return true;
        }
        return false;
    }
}
