package org.example.account;

import org.example.exchange.ConcurrencyMode;
import org.example.exchange.ExchangeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountConcurrencyTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /**
     * Case 4: Double-spending / Overdraft race condition.
     * Alice has $100. Two concurrent client threads both try to spend $80 at the same instant.
     * In SAFE mode: exactly one succeeds; the other is rejected with Insufficient funds.
     */
    @Test
    void safeDoubleSpendPrevention() throws Exception {
        int rounds = 100;
        int violations = 0;

        for (int r = 0; r < rounds; r++) {
            AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
            am.createAccount("alice", new BigDecimal("100.00"), Map.of());

            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Boolean> trySpend = () -> {
                barrier.await();
                try {
                    am.reserveForBuy("alice", 8, new BigDecimal("10.00"), ConcurrencyMode.SAFE, 0);
                    return true;
                } catch (ExchangeException e) {
                    return false;
                }
            };

            Future<Boolean> f1 = pool.submit(trySpend);
            Future<Boolean> f2 = pool.submit(trySpend);

            boolean r1 = f1.get();
            boolean r2 = f2.get();

            // In SAFE mode, exactly one must succeed
            if (r1 && r2) {
                violations++;
            }
            AccountSnapshot snap = am.snapshot("alice");
            assertTrue(snap.cash().signum() >= 0, "Available cash must never be negative in SAFE mode");
        }

        assertEquals(0, violations, "SAFE mode must never allow double-spending or account overdraft");
    }

    /**
     * Case 4 (UNSAFE): When check-then-act happens without the account lock and with a slight race window,
     * both threads see $100 available, both reserve $80, resulting in $160 reserved from a $100 balance (overdraft).
     */
    @Test
    void unsafeDoubleSpendVulnerability() throws Exception {
        int rounds = 50;
        int violations = 0;

        for (int r = 0; r < rounds; r++) {
            AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
            am.createAccount("alice", new BigDecimal("100.00"), Map.of());

            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Boolean> trySpend = () -> {
                barrier.await();
                try {
                    am.reserveForBuy("alice", 8, new BigDecimal("10.00"), ConcurrencyMode.UNSAFE, 2);
                    return true;
                } catch (ExchangeException e) {
                    return false;
                }
            };

            Future<Boolean> f1 = pool.submit(trySpend);
            Future<Boolean> f2 = pool.submit(trySpend);

            boolean r1 = f1.get();
            boolean r2 = f2.get();

            if (r1 && r2) {
                violations++;
            }
        }

        assertTrue(violations > 0, "UNSAFE mode must demonstrate the overdraft / double-spending bug");
    }

    /**
     * Deadlock-free bilateral settlement test:
     * 16 threads concurrently settle trades between random pairs of traders.
     * Without canonical lock ordering, threads locking (A then B) vs (B then A) would deadlock.
     * Canonical lock ordering guarantees zero deadlocks under heavy contention.
     */
    @Test
    void canonicalLockOrderingPreventsDeadlocksUnderHeavyContention() throws Exception {
        AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
        int numTraders = 10;
        BigDecimal initialEach = new BigDecimal("100000.00");
        int initialShares = 10_000;

        for (int i = 0; i < numTraders; i++) {
            am.createAccount("trader_" + i, initialEach, Map.of("ACME", initialShares));
        }

        int rounds = 1000;
        CyclicBarrier barrier = new CyclicBarrier(16);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < 16; t++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < rounds; i++) {
                    int id1 = rnd.nextInt(numTraders);
                    int id2 = rnd.nextInt(numTraders);
                    if (id1 == id2) continue;

                    String buyer = "trader_" + id1;
                    String seller = "trader_" + id2;

                    am.settleTrade(buyer, seller, "ACME", 1, new BigDecimal("10.00"), new BigDecimal("10.00"));
                }
                return null;
            }));
        }

        // Must complete within 5 seconds without deadlocking
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        // System-wide conservation of money and shares check
        AccountManager.InvariantAuditResult audit = am.auditInvariants(List.of());
        assertTrue(audit.passed(), "Invariants must hold after high-concurrency bilateral transfers: " + audit.violations());

        BigDecimal expectedTotalCash = initialEach.multiply(BigDecimal.valueOf(numTraders)).setScale(2, RoundingMode.UNNECESSARY);
        assertEquals(expectedTotalCash, audit.totalCash(), "Total system cash must be strictly conserved");
        assertEquals(initialShares * numTraders, audit.totalShares().get("ACME"), "Total system shares must be strictly conserved");
    }
}
