package com.paytm.wallettransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that run against the already-running app at localhost:8080.
 * No Spring context is started — no data is dropped or recreated.
 * Start the app first: mvn spring-boot:run
 */
public class ConcurrencyLocalTest {

    private static final String BASE_URL = "http://localhost:8080";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> createWallet(int userId, int initialBalance) {
        Map<String, Object> body = Map.of("userId", userId, "initialBalance", initialBalance);
        ResponseEntity<Map> resp = restTemplate.postForEntity(BASE_URL + "/wallets", jsonEntity(body), Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "createWallet failed: " + resp.getStatusCode());
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getWallet(int id) {
        ResponseEntity<Map> resp = restTemplate.getForEntity(BASE_URL + "/wallets/" + id, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transfer(int fromId, int toId, int amount, String key) {
        Map<String, Object> body = Map.of(
                "fromWalletId", fromId,
                "toWalletId", toId,
                "amountPaise", amount,
                "idempotencyKey", key
        );
        ResponseEntity<Map> resp = restTemplate.postForEntity(BASE_URL + "/transfers", jsonEntity(body), Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "transfer failed: " + resp.getStatusCode());
        return resp.getBody();
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private int walletBalance(int id) {
        return (int) getWallet(id).get("balance");
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Conservation: Sum of all wallet balances never changes under concurrent transfers")
    void testBalanceConservationUnderConcurrentTransfers() throws InterruptedException {
        Map<String, Object> w1 = createWallet(unique(), 10000);
        Map<String, Object> w2 = createWallet(unique(), 10000);
        Map<String, Object> w3 = createWallet(unique(), 10000);

        int id1 = (int) w1.get("id");
        int id2 = (int) w2.get("id");
        int id3 = (int) w3.get("id");
        int totalInitialBalance = 30000;

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<int[]> transferPairs = List.of(
                new int[]{id1, id2, 100},
                new int[]{id2, id3, 150},
                new int[]{id3, id1, 200},
                new int[]{id2, id1, 50},
                new int[]{id1, id3, 120},
                new int[]{id3, id2, 80}
        );

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int[] pair = transferPairs.get(index % transferPairs.size());
                    transfer(pair[0], pair[1], pair[2], UUID.randomUUID().toString());
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Transfers should complete within timeout");
        executor.shutdown();

        int finalSum = walletBalance(id1) + walletBalance(id2) + walletBalance(id3);
        assertEquals(totalInitialBalance, finalSum, "Total balance across all wallets must be conserved");
    }

    @Test
    @DisplayName("No Overdraft: Concurrent transfers exceeding balance must fail cleanly and never produce negative balance")
    void testNoOverdraftUnderConcurrentTransfers() throws InterruptedException {
        Map<String, Object> sender = createWallet(unique(), 1000);
        Map<String, Object> receiver = createWallet(unique(), 0);

        int senderId = (int) sender.get("id");
        int receiverId = (int) receiver.get("id");

        int threadCount = 10;
        int transferAmount = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transfer(senderId, receiverId, transferAmount, UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(5, successCount.get(), "Exactly 5 transfers should succeed");
        assertEquals(5, failureCount.get(), "5 transfers should fail cleanly");
        assertEquals(0, walletBalance(senderId), "Sender balance must not be negative");
        assertEquals(1000, walletBalance(receiverId), "Receiver should have received exactly 1000");
    }

    @Test
    @DisplayName("Exactly-once: Concurrent requests with identical idempotency_key result in single debit")
    void testConcurrentIdenticalIdempotencyKey() throws Exception {
        Map<String, Object> sender = createWallet(unique(), 5000);
        Map<String, Object> receiver = createWallet(unique(), 1000);

        int senderId = (int) sender.get("id");
        int receiverId = (int) receiver.get("id");

        String idempotencyKey = "shared-key-" + UUID.randomUUID();
        int threadCount = 10;
        int transferAmount = 500;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                startLatch.await();
                return transfer(senderId, receiverId, transferAmount, idempotencyKey);
            });
        }

        startLatch.countDown();
        List<Future<Map<String, Object>>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        Integer expectedTransferId = null;
        for (Future<Map<String, Object>> future : futures) {
            Map<String, Object> response = future.get();
            assertNotNull(response);
            int tid = (int) response.get("id");
            if (expectedTransferId == null) {
                expectedTransferId = tid;
            } else {
                assertEquals(expectedTransferId, tid, "All concurrent callers must receive the same transfer ID");
            }
            assertEquals("COMPLETED", response.get("status"), "Transfer status must be COMPLETED");
        }

        assertEquals(4500, walletBalance(senderId), "Sender should only be debited once");
    }

    @Test
    @DisplayName("Race-free get-or-create: Concurrent POST /wallets for same userId yield exactly one wallet with no 500 errors")
    void testRaceFreeGetOrCreateWallet() throws InterruptedException {
        int targetUserId = unique();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<ResponseEntity<Map>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Map<String, Object> body = Map.of("userId", targetUserId, "initialBalance", 500);
                    ResponseEntity<Map> resp = restTemplate.postForEntity(BASE_URL + "/wallets", jsonEntity(body), Map.class);
                    responses.add(resp);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount, responses.size());

        Integer expectedWalletId = null;
        for (ResponseEntity<Map> resp : responses) {
            assertTrue(resp.getStatusCode().is2xxSuccessful(), "Response must be 2xx, was: " + resp.getStatusCode());
            assertNotNull(resp.getBody());
            int wid = (int) resp.getBody().get("id");
            if (expectedWalletId == null) {
                expectedWalletId = wid;
            } else {
                assertEquals(expectedWalletId, wid, "All requests should resolve to the same wallet ID");
            }
        }
    }

    @Test
    @DisplayName("Logs: Correlation ID is echoed back and GET /logs returns recorded events")
    void testStructuredLoggingAndCorrelationId() {
        String testCorrelationId = "corr-test-" + UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", testCorrelationId);

        Map<String, Object> body = Map.of("userId", unique(), "initialBalance", 2000);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> walletResp = restTemplate.postForEntity(BASE_URL + "/wallets", entity, Map.class);
        assertEquals(HttpStatus.CREATED, walletResp.getStatusCode());
        assertEquals(testCorrelationId, walletResp.getHeaders().getFirst("X-Correlation-Id"));

        ResponseEntity<List> logsResp = restTemplate.getForEntity(BASE_URL + "/logs", List.class);
        assertEquals(HttpStatus.OK, logsResp.getStatusCode());
        assertNotNull(logsResp.getBody());
        assertFalse(logsResp.getBody().isEmpty(), "Logs endpoint should return recorded events");
    }

    @Test
    @DisplayName("Metrics: All expected keys are present and /metrics/dashboard returns HTML")
    void testMetricsAndDashboard() {
        ResponseEntity<Map> metricsResp = restTemplate.getForEntity(BASE_URL + "/metrics", Map.class);
        assertEquals(HttpStatus.OK, metricsResp.getStatusCode());
        assertNotNull(metricsResp.getBody());
        assertTrue(metricsResp.getBody().containsKey("requestRatePerSec"));
        assertTrue(metricsResp.getBody().containsKey("latencyP99Ms"));
        assertTrue(metricsResp.getBody().containsKey("errorRate"));
        assertTrue(metricsResp.getBody().containsKey("domainCounters"));
        assertTrue(metricsResp.getBody().containsKey("endpoints"));

        ResponseEntity<String> dashResp = restTemplate.getForEntity(BASE_URL + "/metrics/dashboard", String.class);
        assertEquals(HttpStatus.OK, dashResp.getStatusCode());
        assertNotNull(dashResp.getBody());
        assertTrue(dashResp.getBody().contains("Wallet"));
        assertTrue(dashResp.getBody().contains("Per-API Breakdown"));
    }

    // ── utility ────────────────────────────────────────────────────────────────

    /** Returns a unique positive userId that won't collide across test runs. */
    private static int unique() {
        return (int) (System.nanoTime() % 900_000_000L) + 1;
    }
}
