package com.paytm.wallettransfer.metrics;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {

    private final long startTimeMillis = System.currentTimeMillis();

    // Global HTTP metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong errorRequests = new AtomicLong(0);
    private final List<Long> latenciesMillis = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LATENCY_SAMPLES = 5000;

    // Per-endpoint metrics  key = "METHOD /path/pattern"
    private final ConcurrentHashMap<String, EndpointStats> endpointStats = new ConcurrentHashMap<>();

    // Domain counters
    private final ConcurrentHashMap<String, AtomicLong> domainCounters = new ConcurrentHashMap<>();

    // ── recording ──────────────────────────────────────────────────────────────

    public void recordRequest(long durationMillis, int statusCode) {
        recordRequest(durationMillis, statusCode, null);
    }

    public void recordRequest(long durationMillis, int statusCode, String endpointKey) {
        totalRequests.incrementAndGet();
        boolean isError = statusCode >= 400;
        if (isError) errorRequests.incrementAndGet();

        synchronized (latenciesMillis) {
            if (latenciesMillis.size() >= MAX_LATENCY_SAMPLES) latenciesMillis.remove(0);
            latenciesMillis.add(durationMillis);
        }

        if (endpointKey != null) {
            endpointStats
                .computeIfAbsent(endpointKey, k -> new EndpointStats())
                .record(durationMillis, isError);
        }
    }

    public void incrementDomainCounter(String counterName) {
        domainCounters.computeIfAbsent(counterName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public long getDomainCounter(String counterName) {
        AtomicLong val = domainCounters.get(counterName);
        return val != null ? val.get() : 0;
    }

    // ── snapshots ──────────────────────────────────────────────────────────────

    public Map<String, Object> getMetricsSnapshot() {
        long uptimeSeconds = Math.max(1, (System.currentTimeMillis() - startTimeMillis) / 1000);
        long requests = totalRequests.get();
        long errors = errorRequests.get();

        double requestRatePerSec = (double) requests / uptimeSeconds;
        double errorRate = requests > 0 ? (double) errors / requests : 0.0;

        List<Long> sortedLatencies;
        synchronized (latenciesMillis) {
            sortedLatencies = new ArrayList<>(latenciesMillis);
        }
        Collections.sort(sortedLatencies);

        Map<String, Long> counters = new HashMap<>();
        domainCounters.forEach((k, v) -> counters.put(k, v.get()));

        // per-endpoint snapshot — sorted by total requests desc
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpointStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().total.get(), a.getValue().total.get()))
            .forEach(e -> endpoints.put(e.getKey(), e.getValue().snapshot()));

        Map<String, Object> result = new HashMap<>();
        result.put("uptimeSeconds", uptimeSeconds);
        result.put("totalRequests", requests);
        result.put("errorRequests", errors);
        result.put("requestRatePerSec", Math.round(requestRatePerSec * 100.0) / 100.0);
        result.put("errorRate", Math.round(errorRate * 10000.0) / 100.0);
        result.put("latencyP50Ms", getPercentile(sortedLatencies, 0.50));
        result.put("latencyP95Ms", getPercentile(sortedLatencies, 0.95));
        result.put("latencyP99Ms", getPercentile(sortedLatencies, 0.99));
        result.put("domainCounters", counters);
        result.put("endpoints", endpoints);
        return result;
    }

    private double getPercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    // ── inner class ────────────────────────────────────────────────────────────

    public static class EndpointStats {
        final AtomicLong total  = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        private static final int MAX = 1000;

        void record(long durationMs, boolean isError) {
            total.incrementAndGet();
            if (isError) errors.incrementAndGet();
            synchronized (latencies) {
                if (latencies.size() >= MAX) latencies.remove(0);
                latencies.add(durationMs);
            }
        }

        Map<String, Object> snapshot() {
            long t = total.get();
            long e = errors.get();
            List<Long> sorted;
            synchronized (latencies) { sorted = new ArrayList<>(latencies); }
            Collections.sort(sorted);

            double errRate = t > 0 ? Math.round((double) e / t * 10000.0) / 100.0 : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requests", t);
            m.put("errors", e);
            m.put("errorRate", errRate);
            m.put("latencyP50Ms", percentile(sorted, 0.50));
            m.put("latencyP95Ms", percentile(sorted, 0.95));
            m.put("latencyP99Ms", percentile(sorted, 0.99));
            return m;
        }

        private double percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) return 0.0;
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }
}
