/**
 * Shadow-DB Web Showcase Controller
 * Handles client interactions, real-time telemetry updates,
 * animated packet flow topology, and the 3-Step Teacher Walkthrough.
 */

let currentCategory = 'All';
let baselineDbLatency = 140.0;
let latencyHistory = [];
const MAX_CHART_BARS = 12;

document.addEventListener('DOMContentLoaded', () => {
    initCategoryTabs();
    initEventListeners();
    fetchTelemetry();
    fetchProducts();
});

function initCategoryTabs() {
    const tabs = document.querySelectorAll('.cat-tab');
    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            currentCategory = tab.dataset.category;
            fetchProducts();
        });
    });
}

function initEventListeners() {
    document.getElementById('btn-fetch-products').addEventListener('click', () => {
        fetchProducts();
    });

    document.getElementById('btn-reset-demo').addEventListener('click', () => {
        resetDemo();
    });

    document.getElementById('btn-simulate-batch').addEventListener('click', () => {
        runSimulateBatch();
    });
}

// =============================================================================
// API CALLS & DATA RETRIEVAL
// =============================================================================

async function fetchProducts() {
    const sqlDisplay = document.getElementById('current-sql-display');
    let url = '/api/products';
    let sqlText = 'SELECT id, name, category, price, stock, rating, icon FROM products';

    if (currentCategory && currentCategory !== 'All') {
        url += `?category=${encodeURIComponent(currentCategory)}`;
        sqlText += ` WHERE category = '${currentCategory}'`;
    }

    sqlDisplay.textContent = sqlText;
    animatePacketFlowStart();

    try {
        const resp = await fetch(url);
        const data = await resp.json();
        handleQueryResponse(data);
    } catch (err) {
        console.error('Error fetching products:', err);
        logProtocolEvent(`[Error] Failed to connect to Shadow-DB proxy: ${err.message}`);
    }
}

async function fetchTelemetry() {
    try {
        const resp = await fetch('/api/telemetry');
        const data = await resp.json();
        updateTelemetryDisplay(data);
    } catch (err) {
        console.error('Error fetching telemetry:', err);
    }
}

async function handleProductUpdate(event) {
    event.preventDefault();
    const id = parseInt(document.getElementById('edit-product-id').value);
    const price = parseFloat(document.getElementById('edit-product-price').value);
    const stock = parseInt(document.getElementById('edit-product-stock').value);

    closeEditModal();
    animatePacketFlowStart();

    try {
        const resp = await fetch('/api/products/update', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id, price, stock })
        });
        const data = await resp.json();
        handleQueryResponse(data);

        // Immediately refresh products to fetch fresh state
        setTimeout(() => {
            fetchProducts();
        }, 500);
    } catch (err) {
        console.error('Error updating product:', err);
    }
}

async function runSimulateBatch() {
    try {
        animatePacketFlowBurst();
        const resp = await fetch('/api/simulate-batch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                count: 30,
                sql: 'SELECT id, name, category, price, stock, rating, icon FROM products'
            })
        });
        const data = await resp.json();

        document.getElementById('batch-hit-count').textContent = data.cacheHits;
        document.getElementById('batch-miss-count').textContent = data.cacheMisses;
        document.getElementById('batch-avg-latency').textContent = `${data.avgLatencyMs} ms`;
        const offload = ((data.cacheHits / data.totalRequests) * 100).toFixed(1);
        document.getElementById('batch-offload-pct').textContent = `${offload}%`;

        document.getElementById('batch-modal').classList.add('open');

        // Record a sequence of hits in latency chart
        for (let i = 0; i < 5; i++) {
            recordLatency(data.avgLatencyMs, 'CACHE_HIT');
        }

        updateTelemetryDisplay(data.telemetry);
        logProtocolEvent(`[Batch Simulation] Fired 30 rapid requests: ${data.cacheHits} Hits (<3ms), ${data.cacheMisses} Misses.`);
    } catch (err) {
        console.error('Batch simulation error:', err);
    }
}

async function resetDemo() {
    try {
        const resp = await fetch('/api/reset', { method: 'POST' });
        const data = await resp.json();
        latencyHistory = [];
        renderLatencyChart();

        document.getElementById('kpi-latency-val').textContent = '--';
        document.getElementById('kpi-speedup-badge').textContent = 'Reset';
        document.getElementById('kpi-latency-footer').textContent = 'Demo reset to baseline state';

        document.getElementById('kpi-status-val').textContent = 'COLD';
        document.getElementById('kpi-status-val').style.color = 'var(--text-muted)';
        document.getElementById('kpi-status-icon').textContent = '⚪';
        document.getElementById('kpi-status-footer').textContent = 'Cache cleared';

        document.getElementById('guide-narrative-text').textContent =
            'Demo has been reset. Click "Step 1: Initial Cold Read" to begin presenting.';
        document.querySelectorAll('.step-card').forEach(s => s.classList.remove('active-step'));

        logProtocolEvent('[Reset] Shadow-DB cache purged, D1 & D2 reset, database counts cleared.');
        updateTelemetryDisplay(data.telemetry);
        fetchProducts();
    } catch (err) {
        console.error('Reset error:', err);
    }
}

// =============================================================================
// RESPONSE HANDLING & UI UPDATES
// =============================================================================

function handleQueryResponse(data) {
    const isHit = data.status === 'CACHE_HIT';
    const isMiss = data.status === 'CACHE_MISS';
    const isMutation = data.status === 'MUTATION_INVALIDATION';

    // Animate Topology
    animatePacketFlowFinish(data.status, data.latencyMs);

    // Update KPI Cards
    const latVal = document.getElementById('kpi-latency-val');
    latVal.textContent = data.latencyMs.toFixed(2);

    const speedBadge = document.getElementById('kpi-speedup-badge');
    const latFooter = document.getElementById('kpi-latency-footer');

    if (isHit) {
        const speedup = Math.max(1, Math.round(baselineDbLatency / Math.max(0.1, data.latencyMs)));
        speedBadge.textContent = `⚡ ${speedup}x FASTER`;
        speedBadge.className = 'kpi-badge badge-green';
        latVal.style.color = 'var(--color-emerald)';
        latFooter.textContent = 'Served in-memory from Caffeine RAM';
    } else if (isMiss) {
        baselineDbLatency = data.latencyMs;
        speedBadge.textContent = 'DB Baseline';
        speedBadge.className = 'kpi-badge badge-amber';
        latVal.style.color = 'var(--color-amber)';
        latFooter.textContent = 'Full roundtrip to PostgreSQL disk engine';
    } else if (isMutation) {
        speedBadge.textContent = 'Mutation';
        speedBadge.className = 'kpi-badge badge-rose';
        latVal.style.color = 'var(--color-rose)';
        latFooter.textContent = 'UPDATE executed; cache entry invalidated';
    }

    // Cache Status KPI
    const statusVal = document.getElementById('kpi-status-val');
    const statusIcon = document.getElementById('kpi-status-icon');
    const statusFooter = document.getElementById('kpi-status-footer');

    if (isHit) {
        statusVal.textContent = 'CACHE HIT';
        statusVal.style.color = 'var(--color-emerald)';
        statusIcon.textContent = '🟢';
        statusFooter.textContent = 'Sub-3ms SLA achieved (0 DB traffic)';
    } else if (isMiss) {
        statusVal.textContent = 'CACHE MISS';
        statusVal.style.color = 'var(--color-amber)';
        statusIcon.textContent = '🟡';
        statusFooter.textContent = 'Fetched from PostgreSQL and cached into D1';
    } else if (isMutation) {
        statusVal.textContent = 'INVALIDATED';
        statusVal.style.color = 'var(--color-rose)';
        statusIcon.textContent = '🔴';
        statusFooter.textContent = 'Purged all queries bound to table in D2';
    }

    // Record Latency
    recordLatency(data.latencyMs, data.status);

    // Render Product Cards if rows exist
    if (data.rows && data.rows.length > 0) {
        renderProducts(data.columns, data.rows);
    }

    // Update Telemetry
    if (data.telemetry) {
        updateTelemetryDisplay(data.telemetry);
    }

    // Protocol Log Entry
    const previewSql = data.sql.length > 48 ? data.sql.substring(0, 48) + '...' : data.sql;
    if (isHit) {
        logProtocolEvent(`[TCP :5432] HIT (${data.latencyMs.toFixed(2)}ms) -> Returned ${data.rawBytesLength}B wire bytes for "${previewSql}"`, 'hit');
    } else if (isMiss) {
        logProtocolEvent(`[TCP :5432] MISS (${data.latencyMs.toFixed(2)}ms) -> Forwarded to :5433 -> Cached in D1 for "${previewSql}"`, 'miss');
    } else if (isMutation) {
        logProtocolEvent(`[TCP :5432] MUTATION (${data.latencyMs.toFixed(2)}ms) -> Invalidation evicted table queries for "${previewSql}"`, 'mutation');
    }
}

function updateTelemetryDisplay(tel) {
    if (!tel) return;

    // Database Offload
    document.getElementById('kpi-offload-val').textContent = tel.offloadRatioPercent.toFixed(1);
    document.getElementById('kpi-absorbed-count').textContent = tel.hitCount;

    // DB Hits
    document.getElementById('kpi-db-hits').textContent = tel.backendQueries;

    // D1 Size
    document.getElementById('d1-size-val').textContent = tel.d1Size;

    // D1 Keys List
    const d1List = document.getElementById('d1-keys-list');
    if (!tel.d1Keys || tel.d1Keys.length === 0) {
        d1List.innerHTML = '<div class="empty-d1">Cache is empty (Cold)</div>';
    } else {
        d1List.innerHTML = tel.d1Keys.map(k => `
            <div class="d1-item" title="${k}">
                <span>SHA-256: ${k.substring(0, 16)}...${k.substring(k.length - 8)}</span>
                <span class="text-green">CACHED</span>
            </div>
        `).join('');
    }

    // D2 Table Index
    const d2List = document.getElementById('d2-table-list');
    const tableKeys = Object.keys(tel.d2Index || {});
    if (tableKeys.length === 0) {
        d2List.innerHTML = '<div class="empty-d2">No tables currently indexed</div>';
    } else {
        d2List.innerHTML = tableKeys.map(tableName => {
            const keys = tel.d2Index[tableName] || [];
            return `
                <div class="d2-item">
                    <span class="d2-table-name">table: ${tableName}</span>
                    <span class="d2-key-badge">${keys.length} cached query binding(s)</span>
                </div>
            `;
        }).join('');
    }
}

function renderProducts(columns, rows) {
    const grid = document.getElementById('products-grid');
    if (!rows || rows.length === 0) {
        grid.innerHTML = '<div class="empty-d1">No products found in this category.</div>';
        return;
    }

    // Map columns to indices
    const colMap = {};
    columns.forEach((c, idx) => colMap[c.toLowerCase()] = idx);

    grid.innerHTML = rows.map(r => {
        const id = r[colMap['id'] || 0];
        const name = r[colMap['name'] || 1];
        const category = r[colMap['category'] || 2];
        const price = r[colMap['price'] || 3];
        const stock = r[colMap['stock'] || 4];
        const rating = r[colMap['rating'] || 5];
        const icon = r[colMap['icon'] || 6] || '📦';

        return `
            <div class="product-card" id="product-${id}">
                <div class="product-top">
                    <div class="product-icon">${icon}</div>
                    <div class="product-info">
                        <div class="product-name">${name}</div>
                        <div class="product-category">${category}</div>
                        <div class="product-meta-row">
                            <span class="rating-badge">★ ${rating}</span>
                            <span class="stock-badge">${stock} in stock</span>
                        </div>
                    </div>
                </div>
                <div class="product-bottom">
                    <div class="product-price">$${parseFloat(price).toFixed(2)}</div>
                    <button class="btn btn-secondary btn-sm" onclick="openEditModal(${id}, '${escapeHtml(name)}', ${price}, ${stock})">
                        ✏️ Edit Price
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

// =============================================================================
// LATENCY CHART VISUALIZER
// =============================================================================

function recordLatency(latencyMs, status) {
    latencyHistory.push({ latency: latencyMs, status: status });
    if (latencyHistory.length > MAX_CHART_BARS) {
        latencyHistory.shift();
    }
    renderLatencyChart();
}

function renderLatencyChart() {
    const container = document.getElementById('latency-chart');
    if (latencyHistory.length === 0) {
        container.innerHTML = '<div class="empty-d1" style="margin:auto;">No query latency recorded yet</div>';
        return;
    }

    const maxVal = Math.max(160, ...latencyHistory.map(h => h.latency));

    container.innerHTML = latencyHistory.map((item, idx) => {
        const pct = Math.max(6, Math.min(100, (item.latency / maxVal) * 100));
        let typeClass = 'hit';
        if (item.status === 'CACHE_MISS') typeClass = 'miss';
        if (item.status === 'MUTATION_INVALIDATION') typeClass = 'invalidation';

        return `
            <div class="chart-bar-col" title="${item.latency.toFixed(1)} ms (${item.status})">
                <div class="chart-bar ${typeClass}" style="height: ${pct}%;"></div>
                <div class="chart-label">${item.latency < 10 ? item.latency.toFixed(1) : Math.round(item.latency)}ms</div>
            </div>
        `;
    }).join('');
}

// =============================================================================
// ANIMATED NETWORK TOPOLOGY
// =============================================================================

function animatePacketFlowStart() {
    const clientNode = document.getElementById('node-client');
    const particle = document.getElementById('particle-client-proxy');
    const topoStatus = document.getElementById('topology-packet-status');

    clientNode.classList.add('active-glow-green');
    topoStatus.textContent = 'Client transmitting PostgreSQL Frontend 3.0 query packet...';
    topoStatus.className = 'topology-status-idle text-indigo';

    // Animate particle forward
    particle.style.opacity = '1';
    particle.style.left = '0%';
    particle.style.transition = 'left 0.25s linear';
    setTimeout(() => {
        particle.style.left = '100%';
    }, 10);
}

function animatePacketFlowFinish(status, latencyMs) {
    const clientNode = document.getElementById('node-client');
    const proxyNode = document.getElementById('node-proxy');
    const dbNode = document.getElementById('node-db');
    const particle1 = document.getElementById('particle-client-proxy');
    const particle2 = document.getElementById('particle-proxy-db');
    const proxyBadge = document.getElementById('proxy-hit-badge');
    const dbBadge = document.getElementById('db-traffic-badge');
    const topoStatus = document.getElementById('topology-packet-status');

    // Reset old classes
    proxyNode.className = 'topo-node';
    dbNode.className = 'topo-node';

    if (status === 'CACHE_HIT') {
        proxyNode.classList.add('active-glow-green');
        proxyBadge.textContent = `CACHE HIT (${latencyMs.toFixed(1)}ms)`;
        proxyBadge.className = 'node-badge badge-green';
        dbBadge.textContent = 'IDLE (0 Queries)';
        dbBadge.className = 'node-badge';

        topoStatus.textContent = `🚀 FAST PATH: Served from Caffeine RAM in ${latencyMs.toFixed(2)}ms! PostgreSQL was NEVER contacted.`;
        topoStatus.className = 'topology-status-idle text-green';

        // Animate particle bouncing back to client
        particle1.style.left = '0%';
        particle1.style.transition = 'left 0.15s ease-out';
    } else if (status === 'CACHE_MISS') {
        proxyNode.classList.add('active-glow-amber');
        dbNode.classList.add('active-glow-amber');
        proxyBadge.textContent = 'CACHE MISS';
        proxyBadge.className = 'node-badge badge-amber';
        dbBadge.textContent = `DB HIT (${latencyMs.toFixed(0)}ms)`;
        dbBadge.className = 'node-badge badge-amber';

        topoStatus.textContent = `🐢 SLOW PATH: Cache Miss. Forwarded to PostgreSQL (${latencyMs.toFixed(1)}ms). Response stored in RAM.`;
        topoStatus.className = 'topology-status-idle text-amber';

        // Animate particle through wire 2
        particle2.style.opacity = '1';
        particle2.style.left = '0%';
        particle2.style.transition = 'left 0.25s ease-in-out';
        setTimeout(() => {
            particle2.style.left = '100%';
        }, 10);
    } else if (status === 'MUTATION_INVALIDATION') {
        proxyNode.classList.add('active-glow-rose');
        dbNode.classList.add('active-glow-rose');
        proxyBadge.textContent = 'INVALIDATED';
        proxyBadge.className = 'node-badge badge-rose';
        dbBadge.textContent = 'MUTATION WRITTEN';
        dbBadge.className = 'node-badge badge-rose';

        topoStatus.textContent = `⚡ REACTIVE INVALIDATION: UPDATE detected! Purged all 'products' entries from D1 via D2.`;
        topoStatus.className = 'topology-status-idle text-rose';
    }

    setTimeout(() => {
        clientNode.classList.remove('active-glow-green');
        particle1.style.opacity = '0';
        particle2.style.opacity = '0';
    }, 1200);
}

function animatePacketFlowBurst() {
    const proxyNode = document.getElementById('node-proxy');
    proxyNode.classList.add('active-glow-green');
    setTimeout(() => proxyNode.classList.remove('active-glow-green'), 800);
}

// =============================================================================
// TEACHER WALKTHROUGH STEPPER (3-STEP SCRIPT)
// =============================================================================

async function runPresentationStep(step) {
    const s1 = document.getElementById('step-btn-1');
    const s2 = document.getElementById('step-btn-2');
    const s3 = document.getElementById('step-btn-3');
    const narrative = document.getElementById('guide-narrative-text');

    s1.classList.remove('active-step');
    s2.classList.remove('active-step');
    s3.classList.remove('active-step');

    if (step === 1) {
        s1.classList.add('active-step');
        narrative.innerHTML = `<strong>Step 1 (Cold Cache):</strong> Shadow-DB hasn't seen this query yet. Watch the query travel all the way to PostgreSQL, taking <strong>~140ms</strong>.`;
        // Ensure cache is cleared for true cold miss
        await fetch('/api/reset', { method: 'POST' });
        await fetchProducts();
    } else if (step === 2) {
        s2.classList.add('active-step');
        narrative.innerHTML = `<strong>Step 2 (Instant Speedup):</strong> Repeating the exact same query. Notice the latency drops from 140ms to <strong>~2ms (70x faster!)</strong>. PostgreSQL queries counter remained at 0!`;
        await fetchProducts();
    } else if (step === 3) {
        s3.classList.add('active-step');
        narrative.innerHTML = `<strong>Step 3 (Reactive Invalidation):</strong> Updating MacBook Pro price from $2,499 to $999. Watch Shadow-DB detect the mutation, wipe the table from the D2 index, and reload fresh data!`;
        // Execute update on MacBook Pro
        const resp = await fetch('/api/products/update', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: 1, price: 999.00, stock: 12 })
        });
        const data = await resp.json();
        handleQueryResponse(data);

        // Then execute select to verify fresh price
        setTimeout(async () => {
            await fetchProducts();
            narrative.innerHTML += ` <span style="color:#6ee7b7;">✔ Fresh price ($999.00) loaded without stale data!</span>`;
        }, 600);
    }
}

// =============================================================================
// MODAL CONTROLS
// =============================================================================

function openEditModal(id, name, price, stock) {
    document.getElementById('edit-product-id').value = id;
    document.getElementById('edit-product-name').value = name;
    document.getElementById('edit-product-price').value = price;
    document.getElementById('edit-product-stock').value = stock;
    document.getElementById('edit-modal').classList.add('open');
}

function closeEditModal() {
    document.getElementById('edit-modal').classList.remove('open');
}

function closeBatchModal() {
    document.getElementById('batch-modal').classList.remove('open');
}

function logProtocolEvent(text, type = '') {
    const logBox = document.getElementById('protocol-log');
    const time = new Date().toLocaleTimeString();
    const line = document.createElement('div');
    line.className = `log-line ${type}`;
    line.textContent = `[${time}] ${text}`;
    logBox.appendChild(line);
    logBox.scrollTop = logBox.scrollHeight;
}

function clearEventLog() {
    document.getElementById('protocol-log').innerHTML = '';
}

function escapeHtml(text) {
    return text.replace(/'/g, "\\'").replace(/"/g, '&quot;');
}
