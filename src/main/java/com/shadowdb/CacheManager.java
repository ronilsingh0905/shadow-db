package com.shadowdb;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Module 3.0 & 4.0: Caffeine Cache Integration & Reactive Invalidation.
 *
 * D1: Caffeine Cache mapping HashKey -> Result Bytes (sub-3ms lookup).
 * D2: Concurrent Table Index mapping TableName -> Set<HashKey>.
 */
public class    CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    private static final long DEFAULT_MAX_SIZE = 50_000;

    // D1: In-memory result cache
    private final Cache<String, byte[]> d1Cache;

    // D2: Reverse index of TableName -> Set of HashKeys
    private final ConcurrentHashMap<String, Set<String>> d2TableIndex;

    private final AtomicLong hitCounter = new AtomicLong(0);
    private final AtomicLong missCounter = new AtomicLong(0);
    private final AtomicLong invalidationCounter = new AtomicLong(0);

    public CacheManager() {
        this(DEFAULT_MAX_SIZE);
    }

    public CacheManager(long maxSize) {
        this.d1Cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .recordStats()
                .build();
        this.d2TableIndex = new ConcurrentHashMap<>();
    }

    /**
     * Look up cached query response bytes by HashKey.
     *
     * @param hashKey SHA-256 hash of normalized SELECT statement
     * @return Cached response bytes if hit, or null if miss
     */
    public byte[] get(String hashKey) {
        if (hashKey == null) {
            return null;
        }
        byte[] result = d1Cache.getIfPresent(hashKey);
        if (result != null) {
            hitCounter.incrementAndGet();
            log.debug("Cache HIT for key: {}", hashKey);
        } else {
            missCounter.incrementAndGet();
            log.debug("Cache MISS for key: {}", hashKey);
        }
        return result;
    }

    /**
     * Store query result bytes into D1 (Caffeine) and associate with tables in D2.
     *
     * @param hashKey SHA-256 hash of the query
     * @param tableNames Tables accessed by the query
     * @param resultBytes Exact response bytes to cache
     */
    public void put(String hashKey, Collection<String> tableNames, byte[] resultBytes) {
        if (hashKey == null || resultBytes == null) {
            return;
        }

        // Store into D1
        d1Cache.put(hashKey, resultBytes);

        // Map into D2
        if (tableNames != null && !tableNames.isEmpty()) {
            for (String table : tableNames) {
                if (table != null && !table.isBlank()) {
                    String normalizedTable = table.toLowerCase();
                    d2TableIndex.computeIfAbsent(normalizedTable, k -> ConcurrentHashMap.newKeySet()).add(hashKey);
                    log.debug("Indexed D2: table '{}' -> key {}", normalizedTable, hashKey);
                }
            }
        }
    }

    /**
     * Reactive Invalidation: Evicts all cache entries associated with the specified tables.
     *
     * @param tableNames Collection of mutated table names
     * @return Number of evicted cache keys
     */
    public int invalidateTables(Collection<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return 0;
        }

        int totalEvicted = 0;
        for (String table : tableNames) {
            totalEvicted += invalidateTable(table);
        }
        return totalEvicted;
    }

    /**
     * Invalidate all queries cached against a single table.
     *
     * @param tableName Table that was modified by INSERT/UPDATE/DELETE
     * @return Number of keys purged
     */
    public int invalidateTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return 0;
        }

        String normalizedTable = tableName.toLowerCase();
        Set<String> keysToPurge = d2TableIndex.remove(normalizedTable);

        if (keysToPurge != null && !keysToPurge.isEmpty()) {
            d1Cache.invalidateAll(keysToPurge);
            int count = keysToPurge.size();
            invalidationCounter.addAndGet(count);
            log.info("Reactive Invalidation: Purged {} queries for table '{}'", count, normalizedTable);
            return count;
        }

        log.debug("Reactive Invalidation: No cached queries found for table '{}'", normalizedTable);
        return 0;
    }

    /**
     * Clear both D1 and D2 caches completely.
     */
    public void clear() {
        d1Cache.invalidateAll();
        d2TableIndex.clear();
        hitCounter.set(0);
        missCounter.set(0);
        invalidationCounter.set(0);
    }

    public long getD1Size() {
        return d1Cache.estimatedSize();
    }

    public int getD2TableCount() {
        return d2TableIndex.size();
    }

    public Set<String> getKeysForTable(String tableName) {
        Set<String> set = d2TableIndex.get(tableName.toLowerCase());
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    public long getHitCount() {
        return hitCounter.get();
    }

    public long getMissCount() {
        return missCounter.get();
    }

    public long getInvalidationCount() {
        return invalidationCounter.get();
    }
}
