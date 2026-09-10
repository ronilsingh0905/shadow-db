# Shadow-DB: PostgreSQL In-Memory Caching Proxy

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Netty](https://img.shields.io/badge/Networking-Netty%204.x-blue.svg)](https://netty.io/)
[![Caffeine](https://img.shields.io/badge/Cache-Caffeine-red.svg)](https://github.com/ben-manes/caffeine)
[![JSqlParser](https://img.shields.io/badge/Parser-JSqlParser-green.svg)](https://github.com/JSQLParser/JSqlParser)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

**Shadow-DB** is a lightweight, high-performance database caching proxy sitting transparently on the network between your application and a PostgreSQL database.

It intercepts SQL traffic, serves cached `SELECT` query results directly from Caffeine cache with a **sub-3ms SLA**, and **reactively invalidates** table-associated cache keys whenever mutations (`INSERT`, `UPDATE`, `DELETE`) occur on the affected tables.

---

## 🚀 Architecture

```
                      +-------------------------------------------------+
                      |                    Shadow-DB                    |
                      |                                                 |
[Client Socket] <---->|  [ProxyHandler]                                 |
  (Port 5432)         |        |                                        |
                      |        +---> [QueryParser] (JSqlParser)         |
                      |        |         |                              |
                      |        |     (SELECT / Mutation / Other)        |
                      |        |         |                              |
                      |        +---> [CacheManager]                     |
                      |                  |-- D1: Caffeine (HashKey)     |
                      |                  +-- D2: Table Index            |
                      |                          (Table -> Set<HashKey>)|
                      |                                                 |
                      +-------------------------------------------------+
                                        |
                                        v (Miss / Mutation / Raw)
                              [PostgreSQL Socket]
                                  (Port 5433)
```

---

## ⚡ Core Features (50% Core PoC)

1. **Transparent TCP Proxying (Netty 4.x)**
   - Operates on PostgreSQL Frontend Protocol 3.0.
   - Sits on port `5432` forwarding to PostgreSQL on port `5433` (configurable).
   - Zero application code changes required.

2. **AST Query Classification & Hashing (JSqlParser)**
   - Parses incoming SQL Abstract Syntax Trees.
   - Normalizes whitespace and query formatting so equivalent queries hit the same cache.
   - Computes deterministic SHA-256 `HashKey`s.
   - Extracts all referenced table names (including joins).

3. **Dual-Index Caffeine Cache**
   - **`D1` (Result Cache):** High-throughput LRU cache storing exact PostgreSQL wire response bytes.
   - **`D2` (Reverse Table Index):** Maps `TableName -> Set<HashKey>` for instantaneous cache invalidation.

4. **Reactive Cache Invalidation**
   - Intercepts `UPDATE` and `INSERT` mutations in real-time.
   - Looks up `D2` to immediately evict all associated `HashKey`s from `D1`.
   - Guarantees zero stale reads without relying on arbitrary TTL expiration.

---

## 📊 Benchmark Results

From [`ShadowDbPoCTest.java`](src/test/java/com/shadowdb/ShadowDbPoCTest.java):

| Step | Operation | Query Type | Cache State | Database Traffic | Latency | Speedup |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | Initial Read | `SELECT` | CACHE MISS | 1 Query Sent | ~220 ms | Baseline |
| **2** | Repeat Read | `SELECT` | **CACHE HIT** | **0 Queries Sent** | **2.48 ms** | **~88x Faster** |
| **3** | Write Mutation | `UPDATE` | INVALIDATED | 1 Write Sent | ~21 ms | Real-time |
| **4** | Next Read | `SELECT` | CACHE MISS | 1 Query Sent | ~27 ms | Fresh Data |

---

## 🛠️ Getting Started

### Prerequisites
* Java 17 or higher
* Maven 3.8+

### Build & Run Tests
```bash
mvn clean test
```

### Run the Interactive Live Demonstration
```bash
mvn compile exec:java
```

### Run the Proxy Server Standalone
```bash
# Default: listens on 5432, proxies to 127.0.0.1:5433
mvn compile exec:java -Dexec.mainClass="com.shadowdb.Server"

# Custom Ports: <listenPort> <targetHost> <targetPort>
mvn compile exec:java -Dexec.mainClass="com.shadowdb.Server" -Dexec.args="5435 127.0.0.1 5433"
```

---

## 📁 Project Structure

```
shadow-db/
├── pom.xml
├── .gitignore
├── README.md
└── src/
    ├── main/java/com/shadowdb/
    │   ├── Server.java                 # Netty TCP proxy server
    │   ├── ProxyHandler.java           # Pipeline handler & cache interceptor
    │   ├── QueryParser.java            # JSqlParser AST & SHA-256 hasher (Module 1)
    │   ├── CacheManager.java           # D1 Caffeine & D2 reverse index (Module 2)
    │   ├── PostgresProtocolHelper.java # PG 3.0 wire protocol serializer
    │   └── DemoRunner.java             # Standalone interactive showcase
    └── test/java/com/shadowdb/
        └── ShadowDbPoCTest.java        # Comprehensive JUnit 5 test suite
```

---

## 📄 License
MIT License.
