package org.example.exchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Races between traders acting at the same moment on the same shares. */
class StockExchangeConcurrencyTest {
    private static final int ROUNDS = 300;
    private static final BigDecimal TEN = new BigDecimal("10.00");

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static StockExchange newExchange() {
        StockExchange exchange = new StockExchange();
        exchange.listCompany("ACME", "Acme Corp");
        exchange.listCompany("GLOBEX", "Globex Inc");
        return exchange;
    }

    /** Runs all tasks so that they are released at the same instant, and returns their results. */
    @SafeVarargs
    private <T> List<T> simultaneously(Callable<T>... tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    @Test
    void twoBuyersAtTheSameTimeCannotBothBuyTheSameShares() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange();
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 100, TEN);

            List<Order> buys = simultaneously(
                    () -> exchange.placeOrder("alice", "ACME", Side.BUY, 100, TEN),
                    () -> exchange.placeOrder("bob", "ACME", Side.BUY, 100, TEN));

            List<Trade> trades = exchange.trades();
            assertEquals(1, trades.size(), "exactly one buyer gets the offer");
            assertEquals(100, trades.getFirst().quantity());
            assertEquals(OrderStatus.FILLED, exchange.getOrder(offer.id()).status());

            long filled = buys.stream().map(o -> exchange.getOrder(o.id()))
                    .filter(o -> o.status() == OrderStatus.FILLED).count();
            long waiting = buys.stream().map(o -> exchange.getOrder(o.id()))
                    .filter(o -> o.status() == OrderStatus.OPEN && o.quantity() == 100).count();
            assertEquals(1, filled);
            assertEquals(1, waiting, "the other buyer's request stays in the book untouched");
        }
    }

    @Test
    void twoBuyersAtTheSameTimeSplitAnOfferThatCoversOnlyPartOfTheirRequests() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange();
            exchange.placeOrder("seller", "ACME", Side.SELL, 100, TEN);

            List<Order> buys = simultaneously(
                    () -> exchange.placeOrder("alice", "ACME", Side.BUY, 70, TEN),
                    () -> exchange.placeOrder("bob", "ACME", Side.BUY, 70, TEN));

            assertEquals(100, exchange.trades().stream().mapToInt(Trade::quantity).sum(), "never oversold");
            List<Integer> filled = buys.stream().map(o -> exchange.getOrder(o.id()).filled()).sorted().toList();
            assertEquals(List.of(30, 70), filled);
            assertTrue(exchange.openOrders("ACME").stream().noneMatch(o -> o.side() == Side.SELL));
        }
    }

    @Test
    void twoSellersAtTheSameTimeCannotBothSellToTheSameRequest() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange();
            exchange.placeOrder("buyer", "ACME", Side.BUY, 50, TEN);

            simultaneously(
                    () -> exchange.placeOrder("carol", "ACME", Side.SELL, 50, TEN),
                    () -> exchange.placeOrder("dave", "ACME", Side.SELL, 50, TEN));

            assertEquals(1, exchange.trades().size());
            assertEquals(50, exchange.trades().getFirst().quantity());
            assertEquals(1, exchange.openOrders("ACME").size());
        }
    }

    @Test
    void cancelRacingWithABuyEitherCancelsOrTradesButNeverBoth() throws Exception {
        int cancelled = 0;
        int traded = 0;
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange();
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 10, TEN);

            List<Object> results = simultaneously(
                    () -> {
                        try {
                            return exchange.cancelOrder("seller", offer.id());
                        } catch (ExchangeException e) {
                            return e;
                        }
                    },
                    () -> exchange.placeOrder("buyer", "ACME", Side.BUY, 10, TEN));

            boolean cancelSucceeded = results.getFirst() instanceof Order;
            boolean tradeHappened = !exchange.trades().isEmpty();
            assertTrue(cancelSucceeded ^ tradeHappened, "cancel and trade are mutually exclusive");
            if (cancelSucceeded) {
                cancelled++;
                assertEquals(OrderStatus.OPEN, exchange.getOrder(((Order) results.get(1)).id()).status());
            } else {
                traded++;
            }
        }
        assertEquals(ROUNDS, cancelled + traded);
    }

    @Test
    void modifyRacingWithABuyNeverSellsMoreThanOffered() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            StockExchange exchange = newExchange();
            Order offer = exchange.placeOrder("seller", "ACME", Side.SELL, 100, new BigDecimal("11.00"));
            exchange.placeOrder("early", "ACME", Side.BUY, 100, TEN);

            simultaneously(
                    () -> {
                        try {
                            // lowering the price makes the offer match the waiting request of "early"
                            return exchange.modifyOrder("seller", offer.id(), 100, TEN);
                        } catch (ExchangeException e) {
                            return null;
                        }
                    },
                    () -> exchange.placeOrder("late", "ACME", Side.BUY, 100, new BigDecimal("11.00")));

            Order finalOffer = exchange.getOrder(offer.id());
            int sold = exchange.trades().stream().filter(t -> t.sellOrderId() == offer.id())
                    .mapToInt(Trade::quantity).sum();
            assertEquals(100, sold);
            assertEquals(100, finalOffer.filled());
            assertEquals(OrderStatus.FILLED, finalOffer.status());
            assertEquals(1, exchange.trades().size());
        }
    }

    @Test
    void modificationIsRejectedWhileATransactionIsInProgressForTheSameShares() throws Exception {
        StockExchange exchange = new StockExchange(Duration.ofMillis(800), Duration.ofMillis(50));
        exchange.listCompany("ACME", "Acme Corp");
        exchange.listCompany("GLOBEX", "Globex Inc");
        exchange.placeOrder("seller", "ACME", Side.SELL, 10, TEN);
        Order carol = exchange.placeOrder("carol", "ACME", Side.BUY, 5, new BigDecimal("9.00"));
        Order dave = exchange.placeOrder("dave", "GLOBEX", Side.BUY, 5, new BigDecimal("9.00"));

        Future<Order> slowTrade = pool.submit(() -> exchange.placeOrder("buyer", "ACME", Side.BUY, 10, TEN));
        awaitUntil(() -> exchange.isTransactionInProgress("ACME"));

        ExchangeException rejected = assertThrows(ExchangeException.class,
                () -> exchange.modifyOrder("carol", carol.id(), 7, new BigDecimal("9.50")));
        assertTrue(rejected.getMessage().contains("Transaction in progress"));
        assertThrows(ExchangeException.class, () -> exchange.cancelOrder("carol", carol.id()));

        // other companies' shares are not blocked
        assertEquals(7, exchange.modifyOrder("dave", dave.id(), 7, new BigDecimal("9.50")).quantity());

        assertEquals(OrderStatus.FILLED, slowTrade.get().status());
        assertEquals(7, exchange.modifyOrder("carol", carol.id(), 7, new BigDecimal("9.50")).quantity());
    }

    @Test
    void modificationWaitsForAnOngoingTransactionWhenTimeoutAllows() throws Exception {
        StockExchange exchange = new StockExchange(Duration.ofMillis(300), Duration.ofSeconds(5));
        exchange.listCompany("ACME", "Acme Corp");
        exchange.placeOrder("seller", "ACME", Side.SELL, 10, TEN);
        Order carol = exchange.placeOrder("carol", "ACME", Side.BUY, 5, new BigDecimal("9.00"));

        Future<Order> slowTrade = pool.submit(() -> exchange.placeOrder("buyer", "ACME", Side.BUY, 10, TEN));
        awaitUntil(() -> exchange.isTransactionInProgress("ACME"));

        Order modified = exchange.modifyOrder("carol", carol.id(), 8, new BigDecimal("9.10"));

        // The trade is recorded under the lock, so it is visible once the modification got the lock. (Checking
        // slowTrade.isDone() here is racy: the trading thread may not have completed its Future yet.)
        assertEquals(1, exchange.trades().size(), "modification only happened after the transaction completed");
        assertEquals(OrderStatus.FILLED, slowTrade.get().status());
        assertEquals(8, modified.quantity());
    }

    @Test
    void manyTradersActingConcurrentlyKeepTheExchangeConsistent() throws Exception {
        StockExchange exchange = newExchange();
        int traders = 16;
        int actionsPerTrader = 1_000;
        Map<Long, Order> placed = new ConcurrentHashMap<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < traders; t++) {
            String name = "trader" + t;
            tasks.add(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                List<Long> mine = new ArrayList<>();
                for (int i = 0; i < actionsPerTrader; i++) {
                    int action = rnd.nextInt(10);
                    try {
                        if (action < 7 || mine.isEmpty()) {
                            Order o = exchange.placeOrder(name, rnd.nextBoolean() ? "ACME" : "GLOBEX",
                                    rnd.nextBoolean() ? Side.BUY : Side.SELL, 1 + rnd.nextInt(50), randomPrice(rnd));
                            placed.put(o.id(), o);
                            mine.add(o.id());
                        } else if (action < 9) {
                            exchange.modifyOrder(name, mine.get(rnd.nextInt(mine.size())), 1 + rnd.nextInt(50),
                                    randomPrice(rnd));
                        } else {
                            exchange.cancelOrder(name, mine.get(rnd.nextInt(mine.size())));
                        }
                    } catch (ExchangeException expected) {
                        // order already filled or cancelled by the time we got to it
                    }
                }
                return null;
            });
        }
        @SuppressWarnings("unchecked")
        Callable<Void>[] array = tasks.toArray(new Callable[0]);
        simultaneously(array);

        List<Trade> trades = exchange.trades();
        assertEquals(trades.size(), trades.stream().map(Trade::id).collect(Collectors.toSet()).size());
        Map<Long, Integer> tradedPerOrder = new ConcurrentHashMap<>();
        for (Trade t : trades) {
            assertTrue(t.quantity() > 0);
            tradedPerOrder.merge(t.buyOrderId(), t.quantity(), Integer::sum);
            tradedPerOrder.merge(t.sellOrderId(), t.quantity(), Integer::sum);
        }
        for (long id : placed.keySet()) {
            Order o = exchange.getOrder(id);
            assertEquals(tradedPerOrder.getOrDefault(id, 0), o.filled(), "filled shares match trades of #" + id);
            assertEquals(o.status() == OrderStatus.FILLED, o.quantity() == 0);
        }

        Set<Long> open = exchange.openOrders().stream().map(Order::id).collect(Collectors.toSet());
        for (String symbol : List.of("ACME", "GLOBEX")) {
            List<Order> book = exchange.openOrders(symbol);
            assertTrue(book.stream().allMatch(o -> o.status() == OrderStatus.OPEN && o.quantity() > 0));
            BigDecimal bestBid = book.stream().filter(o -> o.side() == Side.BUY).map(Order::price)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal bestAsk = book.stream().filter(o -> o.side() == Side.SELL).map(Order::price)
                    .min(BigDecimal::compareTo).orElse(StockExchange.MAX_PRICE.add(BigDecimal.ONE));
            assertTrue(bestBid.compareTo(bestAsk) < 0, "book must not stay crossed: " + bestBid + " vs " + bestAsk);
        }
        placed.keySet().stream().filter(id -> !open.contains(id))
                .forEach(id -> assertTrue(exchange.getOrder(id).status() != OrderStatus.OPEN));
    }

    private static BigDecimal randomPrice(ThreadLocalRandom rnd) {
        return BigDecimal.valueOf(900 + rnd.nextInt(201), 2);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met in time");
            }
            Thread.sleep(1);
        }
    }
}
