package com.andrew.insighthud;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A tiny HTTP server so a phone on the same Wi-Fi can hand the HUD a destination or a finished route. It speaks just
 * enough HTTP/1.1 for Shortcuts, curl and an app: one request per connection, JSON in and out, and a pairing code in
 * the X-HUD-Token header (or a token= query parameter) on everything but the greeting page.
 *
 *   GET  /            a plain greeting (no token), handy for checking the address from a browser
 *   GET  /status      what the HUD is doing
 *   POST /route       a route to follow (see RouteParser for the JSON); ?precision=6 for polyline6 geometry
 *   POST /destination {"lat":..,"lon":..,"name":".."}: route there on the HUD itself
 *   POST /cancel      stop navigating
 */
final class NavServer {
    private static final String TAG = "HudNav";
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;

    /** Called on a server thread; implementations post to the UI thread themselves. */
    interface Handler {
        String status();
        /** Returns null on success or a message to send back as the error. */
        String route(JSONObject body, int precision);
        String destination(JSONObject body);
        void cancel();
    }

    private final int port;
    private final Handler handler;
    private final java.util.function.Supplier<String> token;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private volatile ServerSocket server;
    private volatile boolean running;

    NavServer(int port, java.util.function.Supplier<String> token, Handler handler) {
        this.port = port;
        this.token = token;
        this.handler = handler;
    }

    void start() {
        if (running) return;
        running = true;
        Thread acceptor = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"))) {
                server = socket;
                Log.i(TAG, "Listening on " + port);
                while (running) {
                    Socket client = socket.accept();
                    Log.i(TAG, "Connection from " + client.getRemoteSocketAddress());
                    workers.execute(() -> serve(client));
                }
            } catch (IOException stopped) {
                if (running) Log.w(TAG, "Navigation receiver stopped", stopped);
            } finally {
                running = false;
            }
        }, "HudNavServer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running = false;
        ServerSocket socket = server;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing only to end the accept loop.
            }
        }
        workers.shutdownNow();
    }

    boolean running() { return running; }

    private void serve(Socket client) {
        try (Socket socket = client) {
            Log.i(TAG, "Serving a request");
            socket.setSoTimeout(6000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) { // read up to the blank line that ends the headers
                int b = in.read();
                if (b < 0) return;
                head.write(b);
                matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
                if (head.size() > MAX_HEADER) {
                    reply(socket.getOutputStream(), 431, error("Headers too large"));
                    return;
                }
            }
            String[] lines = head.toString("ISO-8859-1").split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                reply(socket.getOutputStream(), 400, error("Bad request"));
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
            String target = request[1];
            String path = target, query = "";
            int mark = target.indexOf('?');
            if (mark >= 0) {
                path = target.substring(0, mark);
                query = target.substring(mark + 1);
            }
            Map<String, String> params = new HashMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            byte[] body = new byte[0];
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
                length = 0;
            }
            if (length > MAX_BODY) {
                reply(socket.getOutputStream(), 413, error("Body too large"));
                return;
            }
            if (length > 0) {
                body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            OutputStream out = socket.getOutputStream();
            String method = request[0].toUpperCase(Locale.US);
            if (method.equals("GET") && path.equals("/")) {
                reply(out, 200, "{\"app\":\"insight-hud\",\"navigation\":\"ready\"}");
                return;
            }
            String supplied = headers.containsKey("x-hud-token") ? headers.get("x-hud-token") : params.get("token");
            if (supplied == null || !constantTime(supplied, token.get())) {
                reply(out, 401, error("Missing or wrong pairing code"));
                return;
            }
            if (method.equals("GET") && path.equals("/status")) {
                reply(out, 200, handler.status());
            } else if (method.equals("POST") && path.equals("/cancel")) {
                handler.cancel();
                reply(out, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && (path.equals("/route") || path.equals("/destination"))) {
                JSONObject json;
                try {
                    json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                } catch (JSONException bad) {
                    reply(out, 400, error("Body is not JSON"));
                    return;
                }
                String problem;
                if (path.equals("/route")) {
                    int precision = 5;
                    try {
                        precision = Integer.parseInt(params.getOrDefault("precision", "5"));
                    } catch (NumberFormatException ignored) {
                        precision = 5;
                    }
                    problem = handler.route(json, precision);
                } else {
                    problem = handler.destination(json);
                }
                if (problem == null) reply(out, 200, "{\"ok\":true}");
                else reply(out, 422, error(problem));
            } else {
                reply(out, 404, error("Unknown request"));
            }
        } catch (SocketTimeoutException slow) {
            Log.i(TAG, "A client was too slow");
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "Request failed", failed);
        }
    }

    private static boolean constantTime(String a, String b) {
        if (b == null || a.length() != b.length()) return false;
        int difference = 0;
        for (int i = 0; i < a.length(); i++) difference |= a.charAt(i) ^ b.charAt(i);
        return difference == 0;
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(message) + "}";
    }

    private static void reply(OutputStream out, int code, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized"
                : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : code == 422 ? "Unprocessable Entity"
                : "Error";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
                + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }
}
