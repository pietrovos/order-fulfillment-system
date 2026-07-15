import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIMSHIP: a deliberately unreliable carrier API for testing.
 *
 * <p>Raw sockets instead of an HTTP framework, so faults can be injected below HTTP: the
 * DROP_AFTER_SUCCESS mode commits the booking and then resets the TCP connection without sending a
 * single byte of response, which is exactly the "it worked but we never heard back" case.
 *
 * <pre>
 * POST /shipments            Idempotency-Key required. 201 new, 200 + Idempotent-Replayed: true on replay.
 * GET  /shipments[?idempotencyKey=k]
 * POST /admin/faults         {"mode":"FAIL|TIMEOUT|DROP_AFTER_SUCCESS|SUCCEED","times":n}  (queued, FIFO)
 * GET  /admin/faults
 * POST /admin/reset          forget all shipments and faults
 * GET  /admin/stats
 * GET  /health
 * </pre>
 */
public class CarrierSim {

    enum Mode { SUCCEED, FAIL, TIMEOUT, DROP_AFTER_SUCCESS }

    record Booking(String shipmentId, String trackingNumber, String idempotencyKey, String reference, String createdAt) {
        String json() {
            return "{\"shipmentId\":\"%s\",\"trackingNumber\":\"%s\",\"idempotencyKey\":\"%s\",\"reference\":\"%s\",\"createdAt\":\"%s\"}"
                    .formatted(shipmentId, trackingNumber, esc(idempotencyKey), esc(reference), createdAt);
        }
    }

    record Request(String method, String path, String query, Map<String, String> headers, String body) {
    }

    private static final Map<String, Booking> BOOKINGS = new LinkedHashMap<>();
    private static final Deque<Mode> FAULTS = new ArrayDeque<>();
    private static final AtomicLong SEQ = new AtomicLong(100000);
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong REPLAYS = new AtomicLong();
    private static final long TIMEOUT_MS = Long.parseLong(System.getenv().getOrDefault("TIMEOUT_MS", "30000"));

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8091"));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            log("SIMSHIP carrier simulator listening on :" + port);
            while (true) {
                Socket socket = server.accept();
                pool.submit(() -> handle(socket));
            }
        }
    }

    private static void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10_000);
            Request req = read(socket);
            if (req == null) {
                return;
            }
            route(socket, req);
        } catch (Exception e) {
            log("connection error: " + e);
        }
    }

    private static void route(Socket socket, Request req) throws IOException, InterruptedException {
        String p = req.path();
        if (req.method().equals("GET") && p.equals("/health")) {
            respond(socket, 200, "{\"status\":\"UP\"}", Map.of());
        } else if (req.method().equals("POST") && p.equals("/shipments")) {
            createShipment(socket, req);
        } else if (req.method().equals("GET") && p.equals("/shipments")) {
            String key = queryParam(req.query(), "idempotencyKey");
            List<String> items = new ArrayList<>();
            synchronized (BOOKINGS) {
                BOOKINGS.values().stream().filter(b -> key == null || b.idempotencyKey().equals(key))
                        .forEach(b -> items.add(b.json()));
            }
            respond(socket, 200, "[" + String.join(",", items) + "]", Map.of());
        } else if (req.method().equals("POST") && p.equals("/admin/faults")) {
            Mode mode = Mode.valueOf(field(req.body(), "mode", "FAIL").toUpperCase(Locale.ROOT));
            int times = Integer.parseInt(field(req.body(), "times", "1"));
            synchronized (FAULTS) {
                for (int i = 0; i < times; i++) {
                    FAULTS.addLast(mode);
                }
            }
            log("queued fault " + mode + " x" + times);
            respond(socket, 200, faultsJson(), Map.of());
        } else if (req.method().equals("GET") && p.equals("/admin/faults")) {
            respond(socket, 200, faultsJson(), Map.of());
        } else if (req.method().equals("POST") && p.equals("/admin/reset")) {
            synchronized (BOOKINGS) {
                BOOKINGS.clear();
            }
            synchronized (FAULTS) {
                FAULTS.clear();
            }
            REQUESTS.set(0);
            REPLAYS.set(0);
            respond(socket, 200, "{\"reset\":true}", Map.of());
        } else if (req.method().equals("GET") && p.equals("/admin/stats")) {
            int count;
            synchronized (BOOKINGS) {
                count = BOOKINGS.size();
            }
            respond(socket, 200, "{\"requests\":%d,\"shipments\":%d,\"replays\":%d}"
                    .formatted(REQUESTS.get(), count, REPLAYS.get()), Map.of());
        } else {
            respond(socket, 404, "{\"error\":\"not found\"}", Map.of());
        }
    }

    private static void createShipment(Socket socket, Request req) throws IOException, InterruptedException {
        REQUESTS.incrementAndGet();
        String key = req.headers().get("idempotency-key");
        if (key == null || key.isBlank()) {
            respond(socket, 400, "{\"error\":\"Idempotency-Key header is required\"}", Map.of());
            return;
        }
        Mode mode;
        synchronized (FAULTS) {
            mode = FAULTS.isEmpty() ? Mode.SUCCEED : FAULTS.removeFirst();
        }
        log("POST /shipments key=" + key + " mode=" + mode);
        switch (mode) {
            case FAIL -> respond(socket, 503, "{\"error\":\"carrier temporarily unavailable\"}", Map.of("Retry-After", "1"));
            case TIMEOUT -> {
                // Accept the request, never answer, then hang up. Nothing is booked.
                Thread.sleep(TIMEOUT_MS);
            }
            case DROP_AFTER_SUCCESS -> {
                book(key, req.body());
                // Commit the booking, then reset the connection without sending a response.
                socket.setSoLinger(true, 0);
                socket.close();
            }
            case SUCCEED -> {
                boolean[] replayed = new boolean[1];
                Booking b = book(key, req.body(), replayed);
                if (replayed[0]) {
                    REPLAYS.incrementAndGet();
                    respond(socket, 200, b.json(), Map.of("Idempotent-Replayed", "true"));
                } else {
                    respond(socket, 201, b.json(), Map.of());
                }
            }
        }
    }

    private static Booking book(String key, String body) {
        return book(key, body, new boolean[1]);
    }

    /** Booking is keyed on the idempotency key: the same key always yields the same shipment. */
    private static Booking book(String key, String body, boolean[] replayed) {
        synchronized (BOOKINGS) {
            Booking existing = BOOKINGS.get(key);
            if (existing != null) {
                replayed[0] = true;
                return existing;
            }
            long n = SEQ.incrementAndGet();
            Booking b = new Booking("SIM-" + n, "SS" + (1_000_000_000L + n * 7919 % 1_000_000_000L), key,
                    field(body, "reference", ""), Instant.now().toString());
            BOOKINGS.put(key, b);
            log("booked " + b.shipmentId() + " for key " + key);
            return b;
        }
    }

    // ------------------------------------------------------------------ minimal HTTP/1.1

    private static Request read(Socket socket) throws IOException {
        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        int q = target.indexOf('?');
        Map<String, String> headers = new LinkedHashMap<>();
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) {
                headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        char[] body = new char[length];
        int read = 0;
        while (read < length) {
            int r = in.read(body, read, length - read);
            if (r < 0) {
                break;
            }
            read += r;
        }
        return new Request(parts[0], q >= 0 ? target.substring(0, q) : target, q >= 0 ? target.substring(q + 1) : "",
                headers, new String(body, 0, read));
    }

    private static void respond(Socket socket, int status, String json, Map<String, String> extra) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
        extra.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static String field(String json, String name, String fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json == null ? "" : json);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String queryParam(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) {
                return java.net.URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String faultsJson() {
        synchronized (FAULTS) {
            return "{\"queued\":[" + String.join(",", FAULTS.stream().map(m -> "\"" + m + "\"").toList()) + "]}";
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void log(String msg) {
        System.out.println(Instant.now() + " " + msg);
    }
}
