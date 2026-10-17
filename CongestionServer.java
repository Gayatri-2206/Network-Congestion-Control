
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CongestionServer {

    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {

        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0);

        server.createContext("/api/simulate", exchange -> {

            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(
                    exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(
                    exchange.getRequestMethod())) {
                sendJson(exchange, 405,
                        "{\"error\":\"Use POST request.\"}");
                return;
            }

            try {
               StringBuilder requestBody = new StringBuilder();

try (java.io.BufferedReader reader =
         new java.io.BufferedReader(
             new java.io.InputStreamReader(
                 exchange.getRequestBody(),
                 StandardCharsets.UTF_8))) {

    String line;

    while ((line = reader.readLine()) != null) {
        requestBody.append(line);
    }
}

String body = requestBody.toString();

                int steps = clamp(
                        readInt(body, "steps", 40), 5, 200);

                int bufferSize = clamp(
                        readInt(body, "bufferSize", 20), 5, 100);

                int capacity = clamp(
                        readInt(body, "linkCapacity", 5), 1, 30);

                double randomLoss = clamp(
                        readDouble(body, "randomLoss", 2), 0, 30);

                String mode = readString(body, "mode", "tcp");

                boolean control = !"none".equalsIgnoreCase(mode);

                String result = simulate(
                        steps, bufferSize, capacity,
                        randomLoss, control);

                sendJson(exchange, 200, result);

            } catch (Exception e) {
                sendJson(exchange, 400,
                        "{\"error\":\"Invalid simulation settings.\"}");
            }
        });

        server.createContext("/", exchange -> {
            String message =
                    "Network Congestion Control Server is running.";

            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; charset=UTF-8");

            exchange.sendResponseHeaders(200, data.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });

        server.start();

        System.out.println(
                "========================================");
        System.out.println(
                " NETWORK CONGESTION CONTROL SERVER");
        System.out.println(
                "========================================");
        System.out.println(
                "Server running at http://localhost:8080");
        System.out.println(
                "API: http://localhost:8080/api/simulate");
        System.out.println(
                "Keep this terminal open.");
    }

    private static String simulate(
            int steps,
            int bufferSize,
            int capacity,
            double randomLoss,
            boolean control) {

        Random random = new Random(42);

        int queue = 0;
        int totalSent = 0;
        int totalReceived = 0;
        int totalDropped = 0;

        double cwnd = 1.0;

        List<Integer> queueHistory = new ArrayList<>();
        List<Double> cwndHistory = new ArrayList<>();
        List<Integer> receivedHistory = new ArrayList<>();
        List<Integer> droppedHistory = new ArrayList<>();

        for (int i = 0; i < steps; i++) {

            int offered;

            if (control) {
                offered = Math.max(
                        1, Math.min((int) Math.ceil(cwnd),
                                    capacity * 3));
            } else {
                offered = capacity * 3;
            }

            totalSent += offered;

            // Add packets only if the router buffer has space.
            int available = Math.max(0, bufferSize - queue);
            int accepted = Math.min(offered, available);

            int overflowDrops = offered - accepted;
            queue += accepted;

            // Simulate random packet loss.
            int randomDrops = 0;

            for (int j = 0; j < accepted; j++) {
                if (random.nextDouble() * 100 < randomLoss) {
                    randomDrops++;
                }
            }

            queue -= randomDrops;
            totalDropped += overflowDrops + randomDrops;

            // Forward packets through the outgoing network link.
            int delivered = Math.min(queue, capacity);

            queue -= delivered;
            totalReceived += delivered;

            boolean congested =
                    overflowDrops > 0
                    || randomDrops > 0
                    || queue >= bufferSize * 0.8;

            // Simplified TCP-style congestion window.
            if (control) {
                if (congested) {
                    cwnd = Math.max(1, cwnd / 2);
                } else if (cwnd < 16) {
                    cwnd = Math.min(16, cwnd * 2);
                } else {
                    cwnd += 1;
                }
            } else {
                cwnd = offered;
            }

            queueHistory.add(queue);
            cwndHistory.add(round2(cwnd));
            receivedHistory.add(totalReceived);
            droppedHistory.add(totalDropped);
        }

        double lossRate = totalSent == 0
                ? 0
                : totalDropped * 100.0 / totalSent;

        double throughput = totalReceived / (double) steps;

        return "{"
                + "\"mode\":\"" + (control ? "tcp" : "none") + "\","
                + "\"steps\":" + steps + ","
                + "\"bufferSize\":" + bufferSize + ","
                + "\"linkCapacity\":" + capacity + ","
                + "\"totalSent\":" + totalSent + ","
                + "\"totalReceived\":" + totalReceived + ","
                + "\"totalDropped\":" + totalDropped + ","
                + "\"lossRate\":" + round2(lossRate) + ","
                + "\"throughput\":" + round2(throughput) + ","
                + "\"queueHistory\":" + toJsonInts(queueHistory) + ","
                + "\"cwndHistory\":" + toJsonDoubles(cwndHistory) + ","
                + "\"receivedHistory\":" + toJsonInts(receivedHistory) + ","
                + "\"droppedHistory\":" + toJsonInts(droppedHistory)
                + "}";
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(
            HttpExchange exchange, int status, String json)
            throws IOException {

        addCorsHeaders(exchange);

        byte[] data = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");

        exchange.sendResponseHeaders(status, data.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private static int readInt(
            String json, String key, int fallback) {

        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key)
                        + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);

        return m.find()
                ? Integer.parseInt(m.group(1))
                : fallback;
    }

    private static double readDouble(
            String json, String key, double fallback) {

        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key)
                        + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(json);

        return m.find()
                ? Double.parseDouble(m.group(1))
                : fallback;
    }

    private static String readString(
            String json, String key, String fallback) {

        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key)
                        + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);

        return m.find() ? m.group(1) : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(
            double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String toJsonInts(List<Integer> values) {
        StringBuilder result = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(",");
            result.append(values.get(i));
        }

        return result.append("]").toString();
    }

    private static String toJsonDoubles(List<Double> values) {
        StringBuilder result = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(",");
            result.append(values.get(i));
        }

        return result.append("]").toString();
    }
}
