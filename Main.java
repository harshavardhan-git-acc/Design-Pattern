
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // ================= TRANSACTION =================

    static class Transaction {
        double amount;
        int transfers;
        boolean knownLocation;
        boolean knownDevice;
        int riskScore = 0;
        String decision = "Pending";
        List<String> logs = new ArrayList<>();

        Transaction(double amount, int transfers,
                    boolean knownLocation, boolean knownDevice) {
            this.amount = amount;
            this.transfers = transfers;
            this.knownLocation = knownLocation;
            this.knownDevice = knownDevice;
        }

        void addRisk(int points, String reason) {
            riskScore += points;
            logs.add(reason + " (+" + points + " risk points)");
        }
    }

    // =========== CHAIN OF RESPONSIBILITY ===========

    static abstract class FraudHandler {
        protected FraudHandler next;

        public FraudHandler setNext(FraudHandler next) {
            this.next = next;
            return next;
        }

        public void handle(Transaction transaction) {
            boolean continueChain = process(transaction);

            if (continueChain && next != null) {
                next.handle(transaction);
            }
        }

        protected abstract boolean process(Transaction transaction);
    }

    // Handler 1: Transaction amount
    static class AmountCheckHandler extends FraudHandler {
        protected boolean process(Transaction t) {
            if (t.amount > 300000) {
                t.decision = "Rejected";
                t.logs.add("Critical amount detected. Transaction rejected.");
                return false; // Stop the chain
            }

            if (t.amount > 100000) {
                t.addRisk(15, "Elevated transaction amount");
            } else {
                t.logs.add("Amount check passed");
            }

            return true;
        }
    }

    // Handler 2: Transaction behaviour
    static class BehaviorCheckHandler extends FraudHandler {
        protected boolean process(Transaction t) {
            if (t.transfers >= 5) {
                t.addRisk(25, "Unusual transfer frequency");
            } else {
                t.logs.add("Behaviour check passed");
            }

            return true;
        }
    }

    // Handler 3: Location
    static class LocationCheckHandler extends FraudHandler {
        protected boolean process(Transaction t) {
            if (!t.knownLocation) {
                t.addRisk(20, "Unfamiliar transaction location");
            } else {
                t.logs.add("Location check passed");
            }

            return true;
        }
    }

    // Handler 4: Device
    static class DeviceCheckHandler extends FraudHandler {
        protected boolean process(Transaction t) {
            if (!t.knownDevice) {
                t.addRisk(20, "Unrecognized device detected");
            } else {
                t.logs.add("Device check passed");
            }

            return true;
        }
    }

    // Handler 5: Final risk evaluation
    static class RiskScoringHandler extends FraudHandler {
        protected boolean process(Transaction t) {
            if (t.riskScore >= 65) {
                t.decision = "Rejected";
            } else if (t.riskScore >= 35) {
                t.decision = "Flagged for Manual Review";
            } else {
                t.decision = "Approved";
            }

            t.logs.add("Final risk evaluation completed");
            return true;
        }
    }

    // =============== BUILD THE CHAIN ===============

    static Transaction investigate(
            double amount, int transfers,
            boolean knownLocation, boolean knownDevice) {

        Transaction transaction = new Transaction(
                amount, transfers, knownLocation, knownDevice);

        FraudHandler amountHandler = new AmountCheckHandler();

        amountHandler
                .setNext(new BehaviorCheckHandler())
                .setNext(new LocationCheckHandler())
                .setNext(new DeviceCheckHandler())
                .setNext(new RiskScoringHandler());

        // Start the chain. Each handler decides whether to continue.
        amountHandler.handle(transaction);

        return transaction;
    }

    // ================= HTTP SERVER =================

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(8081), 0);

        server.createContext("/", Main::handleRequest);
        server.start();

        System.out.println("FraudFlow is running at http://localhost:8081");
    }

    static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/investigate")) {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendText(exchange, 405, "POST required");
                return;
            }

            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Map<String, String> data = parseForm(body);

            try {
                double amount = Double.parseDouble(data.get("amount"));
                int transfers = Integer.parseInt(data.get("transfers"));

                boolean knownLocation =
                        "known".equals(data.get("location"));

                boolean knownDevice =
                        "known".equals(data.get("device"));

                Transaction result = investigate(
                        amount, transfers, knownLocation, knownDevice);

                String json = toJson(result);
                sendResponse(exchange, 200, "application/json; charset=UTF-8",
                        json);

            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json; charset=UTF-8",
                        "{\"error\":\"Please enter valid transaction details.\"}");
            }
            return;
        }

        // Serve the website files from the frontend folder.
        String fileName;

        if (path.equals("/") || path.equals("/index.html")) {
            fileName = "index.html";
        } else if (path.equals("/style.css")) {
            fileName = "style.css";
        } else if (path.equals("/script.js")) {
            fileName = "script.js";
        } else {
            sendText(exchange, 404, "Page not found");
            return;
        }

        Path filePath = Path.of("frontend", fileName);

        if (!Files.exists(filePath)) {
            sendText(exchange, 404,
                    "Missing file: " + filePath
                    + ". Make sure you run Java from the FraudFlow folder.");
            return;
        }

        String contentType = fileName.endsWith(".css")
                ? "text/css; charset=UTF-8"
                : fileName.endsWith(".js")
                ? "application/javascript; charset=UTF-8"
                : "text/html; charset=UTF-8";

        sendResponse(exchange, 200, contentType,
                Files.readString(filePath, StandardCharsets.UTF_8));
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> data = new LinkedHashMap<>();

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);

            String key = URLDecoder.decode(
                    parts[0], StandardCharsets.UTF_8);

            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";

            data.put(key, value);
        }

        return data;
    }

    static String toJson(Transaction t) {
        StringBuilder json = new StringBuilder();

        json.append("{");
        json.append("\"decision\":\"").append(escapeJson(t.decision)).append("\",");
        json.append("\"riskScore\":").append(t.riskScore).append(",");
        json.append("\"logs\":[");

        for (int i = 0; i < t.logs.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(t.logs.get(i))).append("\"");
        }

        json.append("]}");
        return json.toString();
    }

    static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    static void sendText(HttpExchange exchange, int status, String text)
            throws IOException {
        sendResponse(exchange, status, "text/plain; charset=UTF-8", text);
    }

    static void sendResponse(HttpExchange exchange, int status,
                             String contentType, String response)
            throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
