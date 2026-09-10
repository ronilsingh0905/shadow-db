package com.shadowdb;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliverable: Single JUnit test class validating the 50% Core PoC of Shadow-DB:
 * 1. A SELECT query populates the cache.
 * 2. The second SELECT query returns instantly from the cache (sub-3ms SLA, no backend I/O).
 * 3. An UPDATE command invalidates the cached SELECT result.
 */
public class ShadowDbPoCTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowDbPoCTest.class);

    private int mockBackendPort;
    private int proxyPort;

    private ServerSocket mockBackendServerSocket;
    private ExecutorService backendExecutor;
    private Server proxyServer;
    private CacheManager cacheManager;

    private final AtomicInteger backendSelectCount = new AtomicInteger(0);
    private final AtomicInteger backendMutationCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        // Allocate free ports for mock backend and proxy
        mockBackendPort = findFreePort();
        proxyPort = findFreePort();

        backendSelectCount.set(0);
        backendMutationCount.set(0);

        // 1. Start mock PostgreSQL backend
        mockBackendServerSocket = new ServerSocket(mockBackendPort);
        mockBackendServerSocket.setReuseAddress(true);
        backendExecutor = Executors.newCachedThreadPool();
        backendExecutor.submit(this::runMockBackend);

        // 2. Start Shadow-DB Proxy
        cacheManager = new CacheManager();
        proxyServer = new Server(proxyPort, "127.0.0.1", mockBackendPort, cacheManager);
        proxyServer.startAsync().sync();

        // Brief warm-up for server socket binding
        TimeUnit.MILLISECONDS.sleep(100);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (mockBackendServerSocket != null && !mockBackendServerSocket.isClosed()) {
            mockBackendServerSocket.close();
        }
        if (backendExecutor != null) {
            backendExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("1. A SELECT query populates the cache")
    void testSelectQueryPopulatesCache() throws Exception {
        String selectSql = "SELECT id, name FROM users WHERE id = 1";

        try (Socket clientSocket = new Socket("127.0.0.1", proxyPort)) {
            clientSocket.setTcpNoDelay(true);

            // Initially cache is empty
            assertThat(cacheManager.getD1Size()).isEqualTo(0);
            assertThat(backendSelectCount.get()).isEqualTo(0);

            // Send SELECT query packet
            byte[] response = sendWireQuery(clientSocket, selectSql);

            // Verify response was received from backend and matches PostgreSQL protocol
            assertThat(response).isNotEmpty();
            assertThat(PostgresProtocolHelper.isReadyForQueryAtEnd(response)).isTrue();

            // Verify backend was queried exactly once
            assertThat(backendSelectCount.get()).isEqualTo(1);

            // Verify cache D1 and table index D2 are populated
            assertThat(cacheManager.getD1Size()).isEqualTo(1);
            assertThat(cacheManager.getKeysForTable("users")).isNotEmpty();
            assertThat(cacheManager.getMissCount()).isEqualTo(1);
            assertThat(cacheManager.getHitCount()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("2. The second SELECT query returns instantly from the cache")
    void testSecondSelectQueryReturnsInstantlyFromCache() throws Exception {
        String selectSql = "SELECT id, name FROM users WHERE id = 1";

        try (Socket clientSocket = new Socket("127.0.0.1", proxyPort)) {
            clientSocket.setTcpNoDelay(true);

            // First query: cache miss, populates cache
            byte[] firstResponse = sendWireQuery(clientSocket, selectSql);
            assertThat(firstResponse).isNotEmpty();
            assertThat(backendSelectCount.get()).isEqualTo(1);
            assertThat(cacheManager.getD1Size()).isEqualTo(1);

            // Second query: cache hit!
            long startTime = System.nanoTime();
            byte[] secondResponse = sendWireQuery(clientSocket, selectSql);
            long durationNs = System.nanoTime() - startTime;
            double durationMs = durationNs / 1_000_000.0;

            log.info("Cache Hit Latency: {} ms", String.format("%.3f", durationMs));

            // Verify the backend was NOT called a second time
            assertThat(backendSelectCount.get()).as("Backend SELECT count should still be 1 on cache hit").isEqualTo(1);

            // Verify the cached response is identical to the first response
            assertThat(secondResponse).isEqualTo(firstResponse);

            // Verify CacheManager metrics
            assertThat(cacheManager.getHitCount()).isEqualTo(1);
            assertThat(cacheManager.getMissCount()).isEqualTo(1);

            // Verify sub-3ms SLA assertion
            assertThat(durationMs).as("Sub-3ms SLA benchmark for in-memory cache hit").isLessThan(3.0);
        }
    }

    @Test
    @DisplayName("3. An UPDATE command invalidates the cached SELECT result")
    void testUpdateCommandInvalidatesCachedSelectResult() throws Exception {
        String selectSql = "SELECT id, name FROM users WHERE id = 1";
        String updateSql = "UPDATE users SET name = 'UpdatedAlice' WHERE id = 1";

        try (Socket clientSocket = new Socket("127.0.0.1", proxyPort)) {
            clientSocket.setTcpNoDelay(true);

            // Step 1: Execute SELECT to populate cache
            byte[] selectResp1 = sendWireQuery(clientSocket, selectSql);
            assertThat(selectResp1).isNotEmpty();
            assertThat(cacheManager.getD1Size()).isEqualTo(1);
            assertThat(cacheManager.getKeysForTable("users")).isNotEmpty();
            assertThat(backendSelectCount.get()).isEqualTo(1);

            // Step 2: Execute UPDATE mutation
            byte[] updateResp = sendWireQuery(clientSocket, updateSql);
            assertThat(updateResp).isNotEmpty();
            assertThat(backendMutationCount.get()).isEqualTo(1);

            // Step 3: Verify cache invalidation occurred reactively
            assertThat(cacheManager.getD1Size()).as("D1 cache should be purged after UPDATE").isEqualTo(0);
            assertThat(cacheManager.getKeysForTable("users")).as("D2 table index should be cleared").isEmpty();
            assertThat(cacheManager.getInvalidationCount()).as("Invalidation count should increment").isGreaterThanOrEqualTo(1);

            // Step 4: Execute SELECT again - must be a cache MISS and hit PostgreSQL backend
            byte[] selectResp2 = sendWireQuery(clientSocket, selectSql);
            assertThat(selectResp2).isNotEmpty();

            // Backend SELECT count must now be 2
            assertThat(backendSelectCount.get()).as("Backend should be queried again after cache invalidation").isEqualTo(2);
            assertThat(cacheManager.getD1Size()).as("Cache should be repopulated with fresh query").isEqualTo(1);
            assertThat(cacheManager.getMissCount()).as("Cache miss count should now be 2").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("4. QueryParser correctly classifies, normalizes, and computes SHA-256 HashKey")
    void testQueryParserClassificationAndHashing() {
        String selectSql = "SELECT  id,   name  FROM  users WHERE id = 42;";
        QueryParser.ParsedQuery parsedSelect = QueryParser.parse(selectSql);

        assertThat(parsedSelect.isSelect()).isTrue();
        assertThat(parsedSelect.tableNames()).contains("users");
        assertThat(parsedSelect.hashKey()).isNotNull();
        assertThat(parsedSelect.hashKey().length()).isEqualTo(64); // SHA-256 hex length

        // Equivalent query with different spacing should produce same HashKey
        String selectSql2 = "SELECT id, name FROM users WHERE id = 42";
        QueryParser.ParsedQuery parsedSelect2 = QueryParser.parse(selectSql2);
        assertThat(parsedSelect2.hashKey()).isEqualTo(parsedSelect.hashKey());

        // Test UPDATE classification
        String updateSql = "UPDATE users SET status = 'ACTIVE' WHERE id = 42";
        QueryParser.ParsedQuery parsedUpdate = QueryParser.parse(updateSql);
        assertThat(parsedUpdate.isMutation()).isTrue();
        assertThat(parsedUpdate.tableNames()).contains("users");

        // Test INSERT classification
        String insertSql = "INSERT INTO orders (order_id, user_id, amount) VALUES (1, 42, 99.9)";
        QueryParser.ParsedQuery parsedInsert = QueryParser.parse(insertSql);
        assertThat(parsedInsert.isMutation()).isTrue();
        assertThat(parsedInsert.tableNames()).contains("orders");
    }

    // --- Helper Methods ---

    private byte[] sendWireQuery(Socket socket, String sql) throws IOException {
        byte[] packet = PostgresProtocolHelper.createQueryPacket(sql);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(packet);
        out.flush();

        ByteArrayOutputStream responseBaos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];

        while (true) {
            int read = in.read(buffer);
            if (read == -1) {
                break;
            }
            responseBaos.write(buffer, 0, read);
            byte[] current = responseBaos.toByteArray();
            if (PostgresProtocolHelper.isReadyForQueryAtEnd(current)) {
                break;
            }
        }
        return responseBaos.toByteArray();
    }

    private void runMockBackend() {
        while (!mockBackendServerSocket.isClosed()) {
            try {
                Socket client = mockBackendServerSocket.accept();
                backendExecutor.submit(() -> handleBackendConnection(client));
            } catch (IOException e) {
                break;
            }
        }
    }

    private void handleBackendConnection(Socket client) {
        try (client;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            byte[] buffer = new byte[8192];
            while (!client.isClosed()) {
                int read = in.read(buffer);
                if (read == -1) break;

                ByteBuf buf = Unpooled.wrappedBuffer(buffer, 0, read);
                String sql = QueryParser.extractSql(buf);

                if (sql != null) {
                    QueryParser.ParsedQuery parsed = QueryParser.parse(sql);
                    if (parsed.isSelect()) {
                        backendSelectCount.incrementAndGet();
                        byte[] response = PostgresProtocolHelper.createMockSelectResponse("name", "Alice");
                        out.write(response);
                        out.flush();
                    } else if (parsed.isMutation()) {
                        backendMutationCount.incrementAndGet();
                        byte[] response = PostgresProtocolHelper.createMockCommandCompleteResponse("UPDATE 1");
                        out.write(response);
                        out.flush();
                    } else {
                        byte[] response = PostgresProtocolHelper.createMockCommandCompleteResponse("OK");
                        out.write(response);
                        out.flush();
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
