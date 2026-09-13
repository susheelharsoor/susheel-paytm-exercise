package com.example.demo.controller;

import com.example.demo.metrics.MetricsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public Map<String, Object> getMetrics() {
        return metricsService.getMetricsSnapshot();
    }

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String getDashboard() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Wallet Service Dashboard</title>
            <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f1f5f9; min-height: 100vh; padding: 1.5rem 2rem 3rem; }
                header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.75rem; border-bottom: 1px solid #1e293b; padding-bottom: 1rem; }
                header h1 { font-size: 1.4rem; font-weight: 700; color: #f1f5f9; }
                header h1 span { color: #38bdf8; }
                #status { font-size: 0.8rem; color: #64748b; display: flex; align-items: center; gap: 6px; }
                .dot { width: 8px; height: 8px; border-radius: 50%%; flex-shrink: 0; }
                .dot.green { background: #4ade80; } .dot.red { background: #f87171; }

                .section-label { font-size: 0.7rem; font-weight: 600; letter-spacing: 0.1em; text-transform: uppercase; color: #475569; margin: 1.75rem 0 0.75rem; }

                /* ── global stat cards ── */
                .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 0.75rem; }
                .card { background: #1e293b; border: 1px solid #334155; border-radius: 0.6rem; padding: 1rem 1.1rem; }
                .card-label { font-size: 0.7rem; color: #64748b; margin-bottom: 0.3rem; text-transform: uppercase; letter-spacing: 0.05em; }
                .card-value { font-size: 1.55rem; font-weight: 700; color: #38bdf8; line-height: 1.1; }
                .card-value.green  { color: #4ade80; }
                .card-value.amber  { color: #fbbf24; }
                .card-value.rose   { color: #f87171; }
                .card-value.purple { color: #a78bfa; }
                .card-sub { font-size: 0.7rem; color: #475569; margin-top: 0.2rem; }

                /* ── domain counters ── */
                .counters { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 0.75rem; }
                .counter-card { background: #1e293b; border: 1px solid #334155; border-radius: 0.6rem; padding: 0.85rem 1rem; display: flex; flex-direction: column; gap: 0.2rem; }
                .counter-label { font-size: 0.7rem; color: #64748b; text-transform: uppercase; letter-spacing: 0.05em; }
                .counter-value { font-size: 1.4rem; font-weight: 700; color: #38bdf8; }

                /* ── per-api table ── */
                .api-table { width: 100%%; border-collapse: collapse; background: #1e293b; border-radius: 0.6rem; overflow: hidden; border: 1px solid #334155; font-size: 0.8rem; }
                .api-table thead tr { background: #0f172a; }
                .api-table th { padding: 0.65rem 1rem; text-align: left; color: #475569; font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.06em; font-weight: 600; white-space: nowrap; }
                .api-table td { padding: 0.65rem 1rem; border-top: 1px solid #334155; white-space: nowrap; }
                .api-table tr:hover td { background: #263045; }
                .method { font-size: 0.65rem; font-weight: 700; padding: 0.18rem 0.4rem; border-radius: 0.25rem; text-transform: uppercase; }
                .m-get  { background: #0e4429; color: #4ade80; }
                .m-post { background: #1e3a5f; color: #60a5fa; }
                .m-del  { background: #4c1a1a; color: #f87171; }
                .path-cell { font-family: "SF Mono", "Fira Code", monospace; color: #e2e8f0; }
                .num { text-align: right; color: #94a3b8; }
                .num.highlight { color: #38bdf8; font-weight: 600; }
                .err-pill { font-size: 0.68rem; padding: 0.1rem 0.4rem; border-radius: 0.8rem; }
                .err-ok  { background: #0e4429; color: #4ade80; }
                .err-low { background: #2d2a00; color: #fbbf24; }
                .err-hi  { background: #4c1a1a; color: #f87171; }

                /* ── log stream ── */
                #log-list { list-style: none; display: flex; flex-direction: column; gap: 0.5rem; max-height: 480px; overflow-y: auto; padding-right: 2px; }
                #log-list li { background: #1e293b; border-left: 3px solid #334155; border-radius: 0.4rem; padding: 0.6rem 0.9rem; display: grid; grid-template-columns: auto auto 1fr; grid-template-rows: auto auto; column-gap: 0.75rem; row-gap: 0.2rem; }
                .log-meta { grid-column: 1 / 4; display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap; }
                .log-time  { font-size: 0.72rem; color: #64748b; font-family: "SF Mono", "Fira Code", monospace; white-space: nowrap; }
                .log-corr  { font-size: 0.68rem; color: #334155; background: #0f172a; padding: 0.1rem 0.4rem; border-radius: 0.25rem; font-family: "SF Mono", "Fira Code", monospace; white-space: nowrap; }
                .log-event { font-size: 0.75rem; font-weight: 700; font-family: "SF Mono", "Fira Code", monospace; white-space: nowrap; }
                .log-detail { grid-column: 1 / 4; font-size: 0.73rem; font-family: "SF Mono", "Fira Code", monospace; color: #94a3b8; display: flex; flex-wrap: wrap; gap: 0.4rem 1rem; margin-top: 0.1rem; }
                .log-kv { color: #94a3b8; }
                .log-kv .kv-key { color: #475569; }
                .log-kv .kv-val { color: #cbd5e1; }
                .ev-wallet_created       { color: #4ade80; }
                .ev-transfer_created     { color: #60a5fa; }
                .ev-debited              { color: #f87171; }
                .ev-credited             { color: #34d399; }
                .ev-idempotent_replay_hit{ color: #a78bfa; }
                .ev-transfer_declined    { color: #fb923c; }
                /* border accent per event type */
                .li-wallet_created       { border-left-color: #4ade80; }
                .li-transfer_created     { border-left-color: #60a5fa; }
                .li-debited              { border-left-color: #f87171; }
                .li-credited             { border-left-color: #34d399; }
                .li-idempotent_replay_hit{ border-left-color: #a78bfa; }
                .li-transfer_declined    { border-left-color: #fb923c; }
            </style>
        </head>
        <body>
            <header>
                <h1>Wallet <span>&amp;</span> Transfer Service</h1>
                <div id="status"><span class="dot green" id="dot"></span><span id="status-text">Loading…</span></div>
            </header>

            <!-- Global Health -->
            <div class="section-label">Global Health</div>
            <div class="cards">
                <div class="card">
                    <div class="card-label">Request Rate</div>
                    <div class="card-value" id="g-rate">—</div>
                    <div class="card-sub">req / sec</div>
                </div>
                <div class="card">
                    <div class="card-label">Error Rate</div>
                    <div class="card-value" id="g-err">—</div>
                    <div class="card-sub" id="g-err-abs">— errors</div>
                </div>
                <div class="card">
                    <div class="card-label">Latency P99</div>
                    <div class="card-value green" id="g-p99">—</div>
                    <div class="card-sub">ms</div>
                </div>
                <div class="card">
                    <div class="card-label">P95 / P50</div>
                    <div class="card-value" id="g-p9550">—</div>
                    <div class="card-sub">ms</div>
                </div>
                <div class="card">
                    <div class="card-label">Total Requests</div>
                    <div class="card-value purple" id="g-total">—</div>
                    <div class="card-sub">since start</div>
                </div>
                <div class="card">
                    <div class="card-label">Uptime</div>
                    <div class="card-value green" id="g-uptime">—</div>
                    <div class="card-sub">seconds</div>
                </div>
            </div>

            <!-- Domain Counters -->
            <div class="section-label">Domain Operations</div>
            <div class="counters">
                <div class="counter-card">
                    <div class="counter-label">Wallets Created</div>
                    <div class="counter-value" id="dc-wallets">—</div>
                </div>
                <div class="counter-card">
                    <div class="counter-label">Transfers Completed</div>
                    <div class="counter-value" id="dc-transfers">—</div>
                </div>
                <div class="counter-card">
                    <div class="counter-label">Transfers Declined</div>
                    <div class="counter-value rose" id="dc-declined">—</div>
                </div>
                <div class="counter-card">
                    <div class="counter-label">Idempotent Replays</div>
                    <div class="counter-value purple" id="dc-replays">—</div>
                </div>
            </div>

            <!-- Per-API Breakdown -->
            <div class="section-label">Per-API Breakdown</div>
            <table class="api-table">
                <thead>
                    <tr>
                        <th>Method</th>
                        <th>Endpoint</th>
                        <th style="text-align:right">Requests</th>
                        <th style="text-align:right">Errors</th>
                        <th style="text-align:right">Error Rate</th>
                        <th style="text-align:right">P50 ms</th>
                        <th style="text-align:right">P95 ms</th>
                        <th style="text-align:right">P99 ms</th>
                    </tr>
                </thead>
                <tbody id="api-tbody">
                    <tr><td colspan="8" style="color:#475569;text-align:center;padding:1.5rem">No data yet — make some requests first.</td></tr>
                </tbody>
            </table>

            <!-- Live Event Log -->
            <div class="section-label">Live Event Log <span style="color:#334155;font-weight:400">(last 30)</span></div>
            <ul id="log-list"><li style="color:#475569;border-left-color:#1e293b">Loading…</li></ul>

            <script>
                function fmt(ts) { return new Date(ts).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'}); }
                function shortCorr(c) { return c ? c.substring(0,8)+'…' : ''; }
                function detailStr(d) { if (!d) return ''; return Object.entries(d).map(([k,v])=>k+'='+v).join('  '); }

                function errPill(rate) {
                    const cls = rate === 0 ? 'err-ok' : rate < 10 ? 'err-low' : 'err-hi';
                    return '<span class="err-pill ' + cls + '">' + rate + '%%</span>';
                }

                function methodBadge(m) {
                    const cls = m === 'GET' ? 'm-get' : m === 'POST' ? 'm-post' : m === 'DELETE' ? 'm-del' : 'm-post';
                    return '<span class="method ' + cls + '">' + m + '</span>';
                }

                async function refreshMetrics() {
                    try {
                        const r = await fetch('/metrics');
                        const m = await r.json();
                        const dc = m.domainCounters || {};
                        const eps = m.endpoints || {};

                        // global cards
                        document.getElementById('g-rate').textContent   = m.requestRatePerSec ?? '—';
                        const er = m.errorRate ?? 0;
                        const erEl = document.getElementById('g-err');
                        erEl.textContent  = er + '%%';
                        erEl.className    = 'card-value ' + (er > 10 ? 'rose' : er > 0 ? 'amber' : 'green');
                        document.getElementById('g-err-abs').textContent = (m.errorRequests ?? 0) + ' errors';
                        document.getElementById('g-p99').textContent     = (m.latencyP99Ms ?? '—') + ' ms';
                        document.getElementById('g-p9550').textContent   = (m.latencyP95Ms ?? '—') + ' / ' + (m.latencyP50Ms ?? '—');
                        document.getElementById('g-total').textContent   = m.totalRequests ?? '—';
                        document.getElementById('g-uptime').textContent  = m.uptimeSeconds ?? '—';

                        // status bar
                        document.getElementById('status-text').textContent = 'Live · uptime ' + m.uptimeSeconds + 's';
                        document.getElementById('dot').className = 'dot green';

                        // domain counters
                        document.getElementById('dc-wallets').textContent   = dc.wallets_created ?? 0;
                        document.getElementById('dc-transfers').textContent  = dc.transfers_created ?? 0;
                        const dec = dc.transfers_declined_insufficient_funds ?? 0;
                        document.getElementById('dc-declined').textContent  = dec;
                        document.getElementById('dc-declined').className    = 'counter-value ' + (dec > 0 ? 'rose' : '');
                        document.getElementById('dc-replays').textContent   = dc.idempotent_replays ?? 0;

                        // per-api table
                        const entries = Object.entries(eps);
                        if (entries.length === 0) return;

                        document.getElementById('api-tbody').innerHTML = entries.map(([key, v]) => {
                            const parts = key.split(' ');
                            const method = parts[0];
                            const path   = parts.slice(1).join(' ');
                            return '<tr>'
                                + '<td>' + methodBadge(method) + '</td>'
                                + '<td class="path-cell">' + path + '</td>'
                                + '<td class="num highlight">' + v.requests + '</td>'
                                + '<td class="num">' + v.errors + '</td>'
                                + '<td class="num">' + errPill(v.errorRate) + '</td>'
                                + '<td class="num">' + v.latencyP50Ms + '</td>'
                                + '<td class="num">' + v.latencyP95Ms + '</td>'
                                + '<td class="num">' + v.latencyP99Ms + '</td>'
                                + '</tr>';
                        }).join('');
                    } catch(e) {
                        document.getElementById('dot').className = 'dot red';
                        document.getElementById('status-text').textContent = 'Unreachable';
                    }
                }

                async function refreshLogs() {
                    try {
                        const r = await fetch('/logs?limit=30');
                        const logs = await r.json();
                        const ul = document.getElementById('log-list');
                        if (!logs.length) { ul.innerHTML = '<li style="color:#475569">No events yet.</li>'; return; }
                        ul.innerHTML = [...logs].reverse().map(e => {
                            const evType = e.eventType || '';
                            const liCls  = 'li-' + evType;
                            const evCls  = 'log-event ev-' + evType;
                            const kvHtml = e.details
                                ? Object.entries(e.details).map(([k,v]) =>
                                    '<span class="log-kv"><span class="kv-key">' + k + '</span>=<span class="kv-val">' + v + '</span></span>'
                                  ).join('')
                                : '';
                            return '<li class="' + liCls + '">'
                                + '<div class="log-meta">'
                                +   '<span class="log-time">' + fmt(e.timestamp) + '</span>'
                                +   '<span class="log-corr">' + shortCorr(e.correlationId) + '</span>'
                                +   '<span class="' + evCls + '">' + evType + '</span>'
                                + '</div>'
                                + '<div class="log-detail">' + kvHtml + '</div>'
                                + '</li>';
                        }).join('');
                    } catch(e) {}
                }

                function tick() { refreshMetrics(); refreshLogs(); }
                tick();
                setInterval(tick, 3000);
            </script>
        </body>
        </html>
        """;
    }
}
