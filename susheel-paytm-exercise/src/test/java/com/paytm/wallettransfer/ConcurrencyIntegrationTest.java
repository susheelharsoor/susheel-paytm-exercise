package com.paytm.wallettransfer;

import com.paytm.wallettransfer.dto.CreateWalletRequest;
import com.paytm.wallettransfer.dto.TransferResponse;
import com.paytm.wallettransfer.dto.WalletResponse;
import com.paytm.wallettransfer.entity.Wallet;
import com.paytm.wallettransfer.repository.TransferRepository;
import com.paytm.wallettransfer.repository.WalletRepository;
import com.paytm.wallettransfer.service.TransferService;
import com.paytm.wallettransfer.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class ConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @BeforeEach
    void cleanDatabase() {
        transferRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Conservation: Sum of all wallet balances never changes under concurrent transfers touching the same wallets")
    void testBalanceConservationUnderConcurrentTransfers() throws InterruptedException {
        // Setup: Create 3 wallets with initial balances
        // Wallet 1: 10,000 paise, Wallet 2: 10,000 paise, Wallet 3: 10,000 paise -> Total: 30,000
        WalletResponse w1 = walletService.getOrCreateWallet(101, 10000);
        WalletResponse w2 = walletService.getOrCreateWallet(102, 10000);
        WalletResponse w3 = walletService.getOrCreateWallet(103, 10000);

        int totalInitialBalance = 30000;
        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<int[]> transferPairs = List.of(
                new int[]{w1.getId(), w2.getId(), 100},
                new int[]{w2.getId(), w3.getId(), 150},
                new int[]{w3.getId(), w1.getId(), 200},
                new int[]{w2.getId(), w1.getId(), 50},
                new int[]{w1.getId(), w3.getId(), 120},
                new int[]{w3.getId(), w2.getId(), 80}
        );

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int[] pair = transferPairs.get(index % transferPairs.size());
                    // wallet owners: w1→userId 101, w2→102, w3→103; pick owner of fromWallet
                    int owner = pair[0] == w1.getId() ? 101 : pair[0] == w2.getId() ? 102 : 103;
                    transferService.initiateTransfer(pair[0], pair[1], pair[2], UUID.randomUUID().toString(), owner);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Transfers should complete within timeout");
        executor.shutdown();

        // Verify Conservation: Total sum of all wallets must remain exactly 30,000
        List<Wallet> wallets = walletRepository.findAll();
        int finalSum = wallets.stream().mapToInt(Wallet::getBalance).sum();
        assertEquals(totalInitialBalance, finalSum, "Total balance across all wallets must be conserved");
    }

    @Test
    @DisplayName("No Overdraft: Concurrent transfers exceeding wallet balance must fail cleanly and never produce negative balance")
    void testNoOverdraftUnderConcurrentTransfers() throws InterruptedException {
        // Setup: Wallet 1 has 1,000 paise. Wallet 2 has 0.
        WalletResponse sender = walletService.getOrCreateWallet(201, 1000);
        WalletResponse receiver = walletService.getOrCreateWallet(202, 0);

        // 10 concurrent transfers of 200 paise each (Total attempted = 2,000 paise, available = 1,000 paise)
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
                    transferService.initiateTransfer(sender.getId(), receiver.getId(), transferAmount, UUID.randomUUID().toString(), 201);
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

        Wallet updatedSender = walletRepository.findById(sender.getId()).orElseThrow();
        Wallet updatedReceiver = walletRepository.findById(receiver.getId()).orElseThrow();

        // Exactly 5 transfers should succeed (5 * 200 = 1000), 5 must fail with InsufficientBalance
        assertEquals(5, successCount.get(), "Exactly 5 transfers should succeed");
        assertEquals(5, failureCount.get(), "5 transfers should fail cleanly");
        assertEquals(0, updatedSender.getBalance(), "Sender balance must not be negative");
        assertEquals(1000, updatedReceiver.getBalance(), "Receiver should have received exactly 1000");
    }

    @Test
    @DisplayName("Exactly-once: Concurrent requests with identical idempotency_key result in single debit and consistent responses")
    void testConcurrentIdenticalIdempotencyKey() throws Exception {
        WalletResponse sender = walletService.getOrCreateWallet(301, 5000);
        WalletResponse receiver = walletService.getOrCreateWallet(302, 1000);

        String idempotencyKey = "shared-idempotency-key-" + UUID.randomUUID();
        int threadCount = 10;
        int transferAmount = 500;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<TransferResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                startLatch.await();
                return transferService.initiateTransfer(sender.getId(), receiver.getId(), transferAmount, idempotencyKey, 301);
            });
        }

        startLatch.countDown();
        List<Future<TransferResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        Integer expectedTransferId = null;
        for (Future<TransferResponse> future : futures) {
            TransferResponse response = future.get();
            assertNotNull(response);
            if (expectedTransferId == null) {
                expectedTransferId = response.getId();
            } else {
                assertEquals(expectedTransferId, response.getId(), "All concurrent callers must receive the same transfer ID");
            }
            assertEquals("COMPLETED", response.getStatus(), "Transfer status must be COMPLETED");
        }

        // Only one transfer record should exist
        assertEquals(1, transferRepository.count());

        // Balance should only be debited once (5000 - 500 = 4500)
        Wallet updatedSender = walletRepository.findById(sender.getId()).orElseThrow();
        assertEquals(4500, updatedSender.getBalance(), "Sender should only be debited once");
    }

    @Test
    @DisplayName("Race-free get-or-create: Concurrent POST /wallets for same userId yield exactly one wallet with no 500 errors")
    void testRaceFreeGetOrCreateWallet() throws InterruptedException {
        int targetUserId = 999;
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<ResponseEntity<WalletResponse>> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CreateWalletRequest request = new CreateWalletRequest();
                    request.setInitialBalance(500);

                    org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
                    h.set("Authorization", "Bearer " + targetUserId);
                    h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    org.springframework.http.HttpEntity<CreateWalletRequest> entity =
                            new org.springframework.http.HttpEntity<>(request, h);

                    ResponseEntity<WalletResponse> resp = restTemplate.postForEntity(
                            getBaseUrl() + "/wallets",
                            entity,
                            WalletResponse.class
                    );
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

        // Exactly 1 wallet entity should exist for this userId in PostgreSQL
        List<Wallet> wallets = walletRepository.findAll().stream()
                .filter(w -> w.getUserId() == targetUserId)
                .toList();
        assertEquals(1, wallets.size(), "Only one wallet should be created in DB");

        int expectedWalletId = wallets.get(0).getId();

        // All concurrent requests must receive a successful response (HTTP 200/201, never 500)
        assertEquals(threadCount, responses.size());
        for (ResponseEntity<WalletResponse> resp : responses) {
            assertTrue(resp.getStatusCode().is2xxSuccessful(), "Response status must be 2xx, was: " + resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals(expectedWalletId, resp.getBody().getId(), "All requests should resolve to the same wallet ID");
        }
    }

    @Test
    @DisplayName("Logs: Correlation ID and domain events are properly structured and publicly viewable via GET /logs")
    void testStructuredLoggingAndCorrelationId() {
        String testCorrelationId = "corr-test-" + UUID.randomUUID();

        // 1. Create wallet with correlation ID header
        CreateWalletRequest wRequest = new CreateWalletRequest();
        wRequest.setInitialBalance(2000);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Correlation-Id", testCorrelationId);
        headers.set("Authorization", "Bearer 888");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        org.springframework.http.HttpEntity<CreateWalletRequest> walletEntity = new org.springframework.http.HttpEntity<>(wRequest, headers);
        ResponseEntity<WalletResponse> walletResp = restTemplate.postForEntity(getBaseUrl() + "/wallets", walletEntity, WalletResponse.class);
        assertEquals(HttpStatus.CREATED, walletResp.getStatusCode());
        assertEquals(testCorrelationId, walletResp.getHeaders().getFirst("X-Correlation-Id"));

        // 2. Query public GET /logs
        ResponseEntity<List> logsResp = restTemplate.getForEntity(getBaseUrl() + "/logs", List.class);
        assertEquals(HttpStatus.OK, logsResp.getStatusCode());
        assertNotNull(logsResp.getBody());
        assertFalse(logsResp.getBody().isEmpty(), "Logs endpoint should return recorded events");
    }

    @Test
    @DisplayName("Metrics: Request rate, latency p99, error rate, and domain counters are exposed via GET /metrics and /metrics/dashboard")
    void testMetricsAndDashboard() {
        // 1. Query /metrics JSON
        ResponseEntity<java.util.Map> metricsResp = restTemplate.getForEntity(getBaseUrl() + "/metrics", java.util.Map.class);
        assertEquals(HttpStatus.OK, metricsResp.getStatusCode());
        assertNotNull(metricsResp.getBody());
        assertTrue(metricsResp.getBody().containsKey("requestRatePerSec"));
        assertTrue(metricsResp.getBody().containsKey("latencyP99Ms"));
        assertTrue(metricsResp.getBody().containsKey("errorRate"));
        assertTrue(metricsResp.getBody().containsKey("domainCounters"));

        // 2. Query /metrics/dashboard HTML
        ResponseEntity<String> dashResp = restTemplate.getForEntity(getBaseUrl() + "/metrics/dashboard", String.class);
        assertEquals(HttpStatus.OK, dashResp.getStatusCode());
        assertNotNull(dashResp.getBody());
        assertTrue(dashResp.getBody().contains("Wallet & Transfer Service"));
        assertTrue(dashResp.getBody().contains("Transfers Completed"));
    }
}
