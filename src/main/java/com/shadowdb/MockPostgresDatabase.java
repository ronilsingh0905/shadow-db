package com.shadowdb;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-fidelity Simulated PostgreSQL Backend Server.
 * Speaks PostgreSQL Frontend/Backend Protocol 3.0.
 * Maintains an in-memory 'products' dataset and simulates realistic database I/O latency (~140ms).
 */
public class MockPostgresDatabase {

    private static final Logger log = LoggerFactory.getLogger(MockPostgresDatabase.class);

    public record Product(int id, String name, String category, double price, int stock, double rating, String icon) {
        public List<String> toRow() {
            return List.of(
                    String.valueOf(id),
                    name,
                    category,
                    String.format(Locale.US, "%.2f", price),
                    String.valueOf(stock),
                    String.format(Locale.US, "%.1f", rating),
                    icon
            );
        }
    }

    public static final List<String> PRODUCT_COLUMNS = List.of(
            "id", "name", "category", "price", "stock", "rating", "icon"
    );

    private final int port;
    private final AtomicInteger queryCount = new AtomicInteger(0);
    private final AtomicInteger mutationCount = new AtomicInteger(0);
    private volatile int simulatedDelayMs = 140;

    private final Map<Integer, Product> products = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;
    private volatile boolean running = false;

    public MockPostgresDatabase(int port) {
        this.port = port;
        resetData();
    }

    public void resetData() {
        products.clear();
        products.put(1, new Product(1, "MacBook Pro M3 Max", "Laptops", 2499.00, 15, 4.9, "💻"));
        products.put(2, new Product(2, "Sony WH-1000XM5 ANC", "Audio", 399.00, 42, 4.8, "🎧"));
        products.put(3, new Product(3, "Keychron Q1 Pro Wireless", "Accessories", 199.00, 28, 4.7, "⌨️"));
        products.put(4, new Product(4, "Dell UltraSharp 32\" 4K", "Displays", 899.00, 9, 4.9, "🖥️"));
        products.put(5, new Product(5, "Logitech MX Master 3S", "Accessories", 99.00, 65, 4.8, "🖱️"));
        products.put(6, new Product(6, "AirPods Pro Gen 2", "Audio", 249.00, 50, 4.7, "🎵"));
    }

    public synchronized void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        serverExecutor = Executors.newCachedThreadPool();
        running = true;

        log.info("Mock PostgreSQL Backend listening on port {}", port);
        serverExecutor.submit(this::acceptConnections);
    }

    private void acceptConnections() {
        ExecutorService workerPool = Executors.newCachedThreadPool();
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                workerPool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            byte[] buffer = new byte[8192];
            while (running && !client.isClosed()) {
                int read = in.read(buffer);
                if (read == -1) break;

                ByteBuf buf = Unpooled.wrappedBuffer(buffer, 0, read);
                String sql = QueryParser.extractSql(buf);

                if (sql != null) {
                    byte[] response = processQuery(sql);
                    out.write(response);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] processQuery(String sql) {
        queryCount.incrementAndGet();

        // Simulate realistic database seek & query execution time
        if (simulatedDelayMs > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(simulatedDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String upper = sql.trim().toUpperCase(Locale.ROOT);

        if (upper.startsWith("SELECT")) {
            return handleSelect(sql);
        } else if (upper.startsWith("UPDATE")) {
            mutationCount.incrementAndGet();
            return handleUpdate(sql);
        } else if (upper.startsWith("INSERT") || upper.startsWith("DELETE")) {
            mutationCount.incrementAndGet();
            return PostgresProtocolHelper.createMockCommandCompleteResponse("OK 1");
        } else {
            return PostgresProtocolHelper.createMockCommandCompleteResponse("COMMAND OK");
        }
    }

    private byte[] handleSelect(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);

        // Filter by category if specified
        String categoryFilter = null;
        Pattern catPattern = Pattern.compile("category\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher catMatcher = catPattern.matcher(sql);
        if (catMatcher.find()) {
            categoryFilter = catMatcher.group(1).trim().toLowerCase(Locale.ROOT);
        }

        List<List<String>> rows = new ArrayList<>();
        List<Product> list = new ArrayList<>(products.values());
        Collections.sort(list, (a, b) -> Integer.compare(a.id(), b.id()));

        for (Product p : list) {
            if (categoryFilter != null && !p.category().toLowerCase(Locale.ROOT).equals(categoryFilter)) {
                continue;
            }
            rows.add(p.toRow());
        }

        return PostgresProtocolHelper.createSelectResponse(PRODUCT_COLUMNS, rows);
    }

    private byte[] handleUpdate(String sql) {
        // e.g. UPDATE products SET price = 899.00 WHERE id = 1
        // or UPDATE products SET price = 899.00, stock = 20 WHERE id = 1
        Pattern pId = Pattern.compile("where\\s+id\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern pPrice = Pattern.compile("price\\s*=\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);
        Pattern pStock = Pattern.compile("stock\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

        Matcher mId = pId.matcher(sql);
        int targetId = mId.find() ? Integer.parseInt(mId.group(1)) : 1;

        Product current = products.get(targetId);
        if (current != null) {
            double newPrice = current.price();
            int newStock = current.stock();

            Matcher mPrice = pPrice.matcher(sql);
            if (mPrice.find()) {
                newPrice = Double.parseDouble(mPrice.group(1));
            }

            Matcher mStock = pStock.matcher(sql);
            if (mStock.find()) {
                newStock = Integer.parseInt(mStock.group(1));
            }

            products.put(targetId, new Product(
                    current.id(),
                    current.name(),
                    current.category(),
                    newPrice,
                    newStock,
                    current.rating(),
                    current.icon()
            ));
            log.info("Mock Postgres: Updated product ID {} -> price={}, stock={}", targetId, newPrice, newStock);
        }

        return PostgresProtocolHelper.createMockCommandCompleteResponse("UPDATE 1");
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    public int getPort() {
        return port;
    }

    public int getQueryCount() {
        return queryCount.get();
    }

    public int getMutationCount() {
        return mutationCount.get();
    }

    public void resetQueryCounts() {
        queryCount.set(0);
        mutationCount.set(0);
    }

    public int getSimulatedDelayMs() {
        return simulatedDelayMs;
    }

    public void setSimulatedDelayMs(int delayMs) {
        this.simulatedDelayMs = delayMs;
    }

    public List<Product> getAllProducts() {
        List<Product> list = new ArrayList<>(products.values());
        Collections.sort(list, (a, b) -> Integer.compare(a.id(), b.id()));
        return list;
    }
}
