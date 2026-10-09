package com.shadowdb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive Web Showcase Server for Shadow-DB.
 * Orchestrates:
 * 1. MockPostgresDatabase (Port 5433)
 * 2. Shadow-DB Proxy (Port 5432)
 * 3. Embedded HTTP Server & API Gateway (Port 8080)
 */
public class ShowcaseServer {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseServer.class);

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_PROXY_PORT = 5432;
    public static final int DEFAULT_DB_PORT = 5433;

    private final int httpPort;
    private final int proxyPort;
    private final int dbPort;

    private MockPostgresDatabase mockDb;
    private CacheManager cacheManager;
    private Server proxyServer;
    private HttpServer httpServer;

    public record QueryExecutionResult(
            String sql,
            String status,
            double latencyMs,
            List<String> columns,
            List<List<String>> rows,
            String commandTag,
            int rawBytesLength
    ) {}

    public ShowcaseServer() {
        this(DEFAULT_HTTP_PORT, DEFAULT_PROXY_PORT, DEFAULT_DB_PORT);
    }

    public ShowcaseServer(int httpPort, int proxyPort, int dbPort) {
        this.httpPort = httpPort;
        this.proxyPort = proxyPort;
        this.dbPort = dbPort;
    }

    public synchronized void start() throws Exception {
        log.info("Booting Shadow-DB Interactive Showcase...");

        // 1. Start Mock PostgreSQL DB
        mockDb = new MockPostgresDatabase(dbPort);
        mockDb.start();
        log.info("1/3 Mock PostgreSQL running on port {}", dbPort);

        // 2. Start Shadow-DB Proxy
        cacheManager = new CacheManager();
        proxyServer = new Server(proxyPort, "127.0.0.1", dbPort, cacheManager);
        proxyServer.startAsync().sync();
        log.info("2/3 Shadow-DB Proxy listening on port {} -> forwarding to {}:{}", proxyPort, "127.0.0.1", dbPort);

        // 3. Start Embedded HTTP Web Server
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.setExecutor(Executors.newCachedThreadPool());

        // Register static and API endpoints
        httpServer.createContext("/", new StaticFileHandler());
        httpServer.createContext("/api/products", new ProductsHandler());
        httpServer.createContext("/api/products/update", new ProductUpdateHandler());
        httpServer.createContext("/api/query", new CustomQueryHandler());
        httpServer.createContext("/api/simulate-batch", new SimulateBatchHandler());
        httpServer.createContext("/api/telemetry", new TelemetryHandler());
        httpServer.createContext("/api/reset", new ResetHandler());

        httpServer.start();
        log.info("3/3 Showcase Web UI ready at http://localhost:{}", httpPort);
        log.info("===============================================================================");
        log.info("🚀 SHADOW-DB SHOWCASE RUNNING: Open http://localhost:{} in your browser", httpPort);
        log.info("===============================================================================");
    }

    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (mockDb != null) {
            mockDb.stop();
        }
        log.info("Showcase stopped.");
    }

    /**
     * Executes SQL through the Shadow-DB TCP proxy over a real socket.
     */
    public QueryExecutionResult executeThroughProxy(String sql) throws IOException {
        long hitBefore = cacheManager.getHitCount();
        long missBefore = cacheManager.getMissCount();
        long invBefore = cacheManager.getInvalidationCount();

        long start = System.nanoTime();
        byte[] responseBytes;
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setTcpNoDelay(true);
            responseBytes = sendSocketQuery(socket, sql);
        }
        long durationNs = System.nanoTime() - start;
        double latencyMs = durationNs / 1_000_000.0;

        long hitAfter = cacheManager.getHitCount();
        long missAfter = cacheManager.getMissCount();
        long invAfter = cacheManager.getInvalidationCount();

        String status = "PASSTHROUGH";
        if (invAfter > invBefore) {
            status = "MUTATION_INVALIDATION";
        } else if (hitAfter > hitBefore) {
            status = "CACHE_HIT";
        } else if (missAfter > missBefore) {
            status = "CACHE_MISS";
        }

        PostgresProtocolHelper.QueryResult decoded = PostgresProtocolHelper.decodeQueryResult(responseBytes);

        return new QueryExecutionResult(
                sql,
                status,
                latencyMs,
                decoded.columns(),
                decoded.rows(),
                decoded.commandTag(),
                responseBytes.length
        );
    }

    private static byte[] sendSocketQuery(Socket socket, String sql) throws IOException {
        byte[] packet = PostgresProtocolHelper.createQueryPacket(sql);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(packet);
        out.flush();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            int read = in.read(buffer);
            if (read == -1) break;
            baos.write(buffer, 0, read);
            byte[] accumulated = baos.toByteArray();
            if (PostgresProtocolHelper.isReadyForQueryAtEnd(accumulated)) {
                break;
            }
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // HTTP Handlers
    // -------------------------------------------------------------------------

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }

            // Look up resource in classpath or fallback
            String resourcePath = "/web" + path;
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    send404(exchange);
                    return;
                }
                byte[] bytes = is.readAllBytes();
                String contentType = "text/html; charset=UTF-8";
                if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
                if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
                if (path.endsWith(".json")) contentType = "application/json; charset=UTF-8";
                if (path.endsWith(".svg")) contentType = "image/svg+xml";

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    private class ProductsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String category = null;
            if (query != null && query.contains("category=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("category=")) {
                        category = param.substring("category=".length()).trim();
                    }
                }
            }

            String sql;
            if (category != null && !category.isBlank() && !category.equalsIgnoreCase("All")) {
                sql = "SELECT id, name, category, price, stock, rating, icon FROM products WHERE category = '" + category + "'";
            } else {
                sql = "SELECT id, name, category, price, stock, rating, icon FROM products";
            }

            QueryExecutionResult res = executeThroughProxy(sql);
            String json = toJson(res);
            sendJson(exchange, 200, json);
        }
    }

    private class ProductUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Parse id, price, stock from simple JSON
            int id = extractInt(body, "id", 1);
            double price = extractDouble(body, "price", 899.0);
            int stock = extractInt(body, "stock", 15);

            String sql = String.format(Locale.US, "UPDATE products SET price = %.2f, stock = %d WHERE id = %d", price, stock, id);
            QueryExecutionResult res = executeThroughProxy(sql);
            String json = toJson(res);
            sendJson(exchange, 200, json);
        }
    }

    private class CustomQueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sql = extractString(body, "sql", "SELECT id, name, category, price, stock, rating, icon FROM products");

            QueryExecutionResult res = executeThroughProxy(sql);
            String json = toJson(res);
            sendJson(exchange, 200, json);
        }
    }

    private class SimulateBatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int count = extractInt(body, "count", 30);
            String sql = extractString(body, "sql", "SELECT id, name, category, price, stock, rating, icon FROM products");

            List<Double> latencies = new ArrayList<>();
            int hitCount = 0;
            int missCount = 0;

            for (int i = 0; i < count; i++) {
                QueryExecutionResult r = executeThroughProxy(sql);
                latencies.add(r.latencyMs());
                if ("CACHE_HIT".equals(r.status())) hitCount++;
                if ("CACHE_MISS".equals(r.status())) missCount++;
            }

            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = 0;
            for (double l : latencies) {
                sum += l;
                if (l < min) min = l;
                if (l > max) max = l;
            }
            double avg = latencies.isEmpty() ? 0 : sum / latencies.size();

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalRequests\":").append(count).append(",");
            sb.append("\"cacheHits\":").append(hitCount).append(",");
            sb.append("\"cacheMisses\":").append(missCount).append(",");
            sb.append(String.format(Locale.US, "\"avgLatencyMs\":%.2f,", avg));
            sb.append(String.format(Locale.US, "\"minLatencyMs\":%.2f,", min));
            sb.append(String.format(Locale.US, "\"maxLatencyMs\":%.2f,", max));
            sb.append("\"telemetry\":").append(getTelemetryJson());
            sb.append("}");

            sendJson(exchange, 200, sb.toString());
        }
    }

    private class TelemetryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            sendJson(exchange, 200, getTelemetryJson());
        }
    }

    private class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            cacheManager.clear();
            mockDb.resetData();
            mockDb.resetQueryCounts();
            sendJson(exchange, 200, "{\"success\":true,\"message\":\"Reset successful\",\"telemetry\":" + getTelemetryJson() + "}");
        }
    }

    private String getTelemetryJson() {
        long hits = cacheManager.getHitCount();
        long misses = cacheManager.getMissCount();
        long inv = cacheManager.getInvalidationCount();
        long d1Size = cacheManager.getD1Size();
        long dbQueries = mockDb.getQueryCount();
        long dbMutations = mockDb.getMutationCount();

        long totalOps = hits + misses;
        double offloadRatio = (totalOps > 0) ? (hits * 100.0 / totalOps) : 100.0;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"hitCount\":").append(hits).append(",");
        sb.append("\"missCount\":").append(misses).append(",");
        sb.append("\"invalidationCount\":").append(inv).append(",");
        sb.append("\"d1Size\":").append(d1Size).append(",");
        sb.append("\"backendQueries\":").append(dbQueries).append(",");
        sb.append("\"backendMutations\":").append(dbMutations).append(",");
        sb.append(String.format(Locale.US, "\"offloadRatioPercent\":%.1f,", offloadRatio));
        sb.append("\"simulatedDelayMs\":").append(mockDb.getSimulatedDelayMs()).append(",");
        sb.append("\"proxyPort\":").append(proxyPort).append(",");
        sb.append("\"dbPort\":").append(dbPort).append(",");

        // D1 Keys
        sb.append("\"d1Keys\":[");
        Set<String> keys = cacheManager.getD1Keys();
        int kIdx = 0;
        for (String k : keys) {
            if (kIdx++ > 0) sb.append(",");
            sb.append("\"").append(escape(k)).append("\"");
        }
        sb.append("],");

        // D2 Index
        sb.append("\"d2Index\":{");
        Map<String, Set<String>> d2 = cacheManager.getD2TableIndex();
        int d2Idx = 0;
        for (Map.Entry<String, Set<String>> e : d2.entrySet()) {
            if (d2Idx++ > 0) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":[");
            int sIdx = 0;
            for (String h : e.getValue()) {
                if (sIdx++ > 0) sb.append(",");
                sb.append("\"").append(escape(h)).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    private String toJson(QueryExecutionResult res) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"sql\":\"").append(escape(res.sql())).append("\",");
        sb.append("\"status\":\"").append(res.status()).append("\",");
        sb.append(String.format(Locale.US, "\"latencyMs\":%.2f,", res.latencyMs()));
        sb.append("\"commandTag\":\"").append(escape(res.commandTag())).append("\",");
        sb.append("\"rawBytesLength\":").append(res.rawBytesLength()).append(",");

        // Columns
        sb.append("\"columns\":[");
        for (int i = 0; i < res.columns().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(res.columns().get(i))).append("\"");
        }
        sb.append("],");

        // Rows
        sb.append("\"rows\":[");
        for (int i = 0; i < res.rows().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("[");
            List<String> row = res.rows().get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append(",");
                String val = row.get(j);
                if (val == null) {
                    sb.append("null");
                } else {
                    sb.append("\"").append(escape(val)).append("\"");
                }
            }
            sb.append("]");
        }
        sb.append("],");

        sb.append("\"telemetry\":").append(getTelemetryJson());
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static int extractInt(String json, String key, int def) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static double extractDouble(String json, String key, double def) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\d.]+)");
        Matcher m = p.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }

    private static String extractString(String json, String key, String def) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : def;
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void send404(HttpExchange ex) throws IOException {
        byte[] bytes = "{\"error\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(404, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) {
        int httpPort = DEFAULT_HTTP_PORT;
        int proxyPort = DEFAULT_PROXY_PORT;
        int dbPort = DEFAULT_DB_PORT;

        if (args.length >= 1) httpPort = Integer.parseInt(args[0]);
        if (args.length >= 2) proxyPort = Integer.parseInt(args[1]);
        if (args.length >= 3) dbPort = Integer.parseInt(args[2]);

        ShowcaseServer showcase = new ShowcaseServer(httpPort, proxyPort, dbPort);
        Runtime.getRuntime().addShutdownHook(new Thread(showcase::stop));

        try {
            showcase.start();
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Fatal error running ShowcaseServer", e);
        }
    }
}
