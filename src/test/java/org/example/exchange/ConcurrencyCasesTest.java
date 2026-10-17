package org.example.exchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three concurrency cases, each run against the correct ({@link ConcurrencyMode#SAFE}) and the intentionally
 * broken ({@link ConcurrencyMode#UNSAFE}) version of {@link StockExchange#buyFromOffer}. The safe version must never
 * break the rule; the unsafe version is expected to break it, and the test counts how often.
 * Results are printed so they can be copied into CONCURRENCY.md.
 */
class ConcurrencyCasesTest {
    private static final int ROUNDS = 200;
    /** Simulated transaction time; it is the window between "check" and "act" in the unsafe version. */
    private static final Duration SETTLEMENT = Duration.ofMillis(2);
    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final BigDecimal TWELVE = new BigDecimal("12.00");

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static StockExchange newExchange(ConcurrencyMode mode) {
        StockExchange exchange = new StockExchange(SETTLEMENT, Duration.ofSeconds(2), mode);
        exchange.listCompany("ACME", "Acme Corp");
        return exchange;
    }

    /** Releases all tasks at the same instant; a task rejected with an {@link ExchangeException} yields null. */
    @SafeVarargs
    private <T> List<T> simultaneously(Callable<T>... tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (ExchangeException rejected) {
                    return null;
                }
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    /** Busy-waits; Thread.sleep is rounded up to the OS timer tick (~15 ms on Windows), which is too coarse. */
    private static void pause(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    private static int sharesSold(StockExchange exchange) {
        return exchange.trades().stream().mapToInt(Trade::quantity).sum();
    }

    private static void report(String scenario, ConcurrencyMode mode, int violations, long startNanos) {
        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("[%s] %-6s violations: %3d/%d rounds (%.0f%%), time: %d ms%n",
                scenario, mode, violations, ROUNDS, 100.0 * violations / ROUNDS, millis);
    }

    // ---- Case 1: the price goes up while the client is buying at the price they saw -------------------------

    /**
     * Buyer saw 10.00 and agreed to pay that; while the purchase is in progress (0.5 ms later) the seller raises the
     * offer to 12.00.
     */
    private int priceLock(ConcurrencyMode mode) throws Exception {
        int violations = 0;
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange(mode);
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 100, TEN);

            simultaneously(
                    () -> exchange.buyFromOffer("alice", offer.id(), 100, TEN),
                    () -> {
                        pause(Duration.ofNanos(500_000));
                        return exchange.modifyOrder("seller", offer.id(), 100, TWELVE);
                    });

            boolean paidMore = exchange.trades().stream().anyMatch(t -> t.price().compareTo(TEN) > 0);
            if (paidMore) {
                violations++;
            }
        }
        report("price lock", mode, violations, start);
        return violations;
    }

    @Test
    void safe_buyerNeverPaysMoreThanThePriceTheySaw() throws Exception {
        assertEquals(0, priceLock(ConcurrencyMode.SAFE));
    }

    @Test
    void unsafe_buyerSometimesPaysTheRaisedPrice() throws Exception {
        assertTrue(priceLock(ConcurrencyMode.UNSAFE) > 0, "the check-then-act race should show up");
    }

    // ---- Case 2: two clients buy the same shares at the same time -------------------------------------------

    /** One offer of 100 shares; alice and bob each try to buy all 100 at the same moment. */
    private int sameShares(ConcurrencyMode mode) throws Exception {
        int violations = 0;
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange(mode);
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 100, TEN);

            List<Order> buys = simultaneously(
                    () -> exchange.buyFromOffer("alice", offer.id(), 100, TEN),
                    () -> exchange.buyFromOffer("bob", offer.id(), 100, TEN));

            long successful = buys.stream().filter(b -> b != null).count();
            if (sharesSold(exchange) != 100 || successful != 1) {
                violations++;
            }
        }
        report("same shares", mode, violations, start);
        return violations;
    }

    @Test
    void safe_onlyOneOfTwoBuyersGetsTheShares() throws Exception {
        assertEquals(0, sameShares(ConcurrencyMode.SAFE));
    }

    @Test
    void unsafe_bothBuyersGetTheSameShares() throws Exception {
        assertTrue(sameShares(ConcurrencyMode.UNSAFE) > 0, "the same shares should sometimes be sold twice");
    }

    // ---- Case 3: X + Y requested, fewer than X + Y available ------------------------------------------------

    /** One offer of 100 shares; alice wants 70 and bob wants 50 (120 in total) at the same moment. */
    private int notEnoughShares(ConcurrencyMode mode) throws Exception {
        int violations = 0;
        long start = System.nanoTime();
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange(mode);
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 100, TEN);

            simultaneously(
                    () -> exchange.buyFromOffer("alice", offer.id(), 70, TEN),
                    () -> exchange.buyFromOffer("bob", offer.id(), 50, TEN));

            // Correct outcomes: 70 + 30 (alice first) or 50 + 50 (bob first); always exactly 100 sold, 0 left.
            Order after = exchange.getOrder(offer.id());
            if (sharesSold(exchange) != 100 || after.quantity() != 0) {
                violations++;
            }
        }
        report("X+Y > available", mode, violations, start);
        return violations;
    }

    @Test
    void safe_secondBuyerGetsOnlyWhatIsLeft() throws Exception {
        assertEquals(0, notEnoughShares(ConcurrencyMode.SAFE));
    }

    @Test
    void unsafe_moreSharesAreSoldThanOffered() throws Exception {
        assertTrue(notEnoughShares(ConcurrencyMode.UNSAFE) > 0, "the offer should sometimes go negative");
    }
}
