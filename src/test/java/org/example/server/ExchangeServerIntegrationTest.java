package org.example.server;

import org.example.client.ExchangeClient;
import org.example.exchange.ExchangeException;
import org.example.exchange.Order;
import org.example.exchange.OrderStatus;
import org.example.exchange.StockExchange;
import org.example.exchange.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** End-to-end tests: real TCP clients talking to a real server. */
class ExchangeServerIntegrationTest {
    private static final BigDecimal TEN = new BigDecimal("10.00");

    private StockExchange exchange;
    private ExchangeServer server;
    private final List<ExchangeClient> clients = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    /** Records everything a client receives from the server. */
    private static final class Recorder implements ExchangeClient.Listener {
        final List<Trade> trades = new CopyOnWriteArrayList<>();
        final Map<Long, Order> orders = new ConcurrentHashMap<>();
        final List<String> symbols = new CopyOnWriteArrayList<>();

        @Override
        public void onSymbol(String symbol, String companyName) {
            symbols.add(symbol);
        }

        @Override
        public void onOrder(Order order) {
            orders.put(order.id(), order);
        }

        @Override
        public void onTrade(Trade trade) {
            trades.add(trade);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        exchange = new StockExchange();
        exchange.listCompany("ACME", "Acme Corp");
        server = new ExchangeServer(exchange, 0);
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        for (ExchangeClient c : clients) {
            c.close();
        }
        server.close();
        pool.shutdownNow();
    }

    private ExchangeClient connect(String name, Recorder recorder) throws Exception {
        ExchangeClient client = new ExchangeClient("localhost", server.port(), recorder);
        clients.add(client);
        client.login(name).get(5, TimeUnit.SECONDS);
        return client;
    }

    @Test
    void twoClientsBuyingTheSameOfferAtTheSameTimeOverTheNetwork() throws Exception {
        Recorder sellerView = new Recorder();
        Recorder aliceView = new Recorder();
        Recorder bobView = new Recorder();
        ExchangeClient seller = connect("seller", sellerView);
        ExchangeClient alice = connect("alice", aliceView);
        ExchangeClient bob = connect("bob", bobView);

        int rounds = 25;
        for (int round = 0; round < rounds; round++) {
            seller.sell("ACME", 10, TEN).get(5, TimeUnit.SECONDS);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Long> a = pool.submit(() -> {
                barrier.await();
                return alice.buy("ACME", 10, TEN).get(5, TimeUnit.SECONDS);
            });
            Future<Long> b = pool.submit(() -> {
                barrier.await();
                return bob.buy("ACME", 10, TEN).get(5, TimeUnit.SECONDS);
            });
            long aliceOrder = a.get();
            long bobOrder = b.get();

            Order aliceState = exchange.getOrder(aliceOrder);
            Order bobState = exchange.getOrder(bobOrder);
            assertTrue(aliceState.status() == OrderStatus.FILLED ^ bobState.status() == OrderStatus.FILLED,
                    "exactly one of the simultaneous buyers gets the 10 shares");
            // withdraw the losing request so the next round starts from a clean book
            if (aliceState.status() == OrderStatus.OPEN) {
                alice.cancel(aliceOrder).get(5, TimeUnit.SECONDS);
            } else {
                bob.cancel(bobOrder).get(5, TimeUnit.SECONDS);
            }
        }

        assertEquals(rounds, exchange.trades().size());
        assertEquals(rounds * 10, exchange.trades().stream().mapToInt(Trade::quantity).sum());
        // every participant sees the whole transaction history
        awaitUntil(() -> sellerView.trades.size() == rounds && aliceView.trades.size() == rounds
                && bobView.trades.size() == rounds);
    }

    @Test
    void newClientReceivesSymbolsOrdersAndHistoryOnLogin() throws Exception {
        ExchangeClient seller = connect("seller", new Recorder());
        long offer = seller.sell("ACME", 10, TEN).get(5, TimeUnit.SECONDS);
        seller.sell("ACME", 5, new BigDecimal("12.00")).get(5, TimeUnit.SECONDS);
        connect("buyer", new Recorder()).buy("ACME", 10, TEN).get(5, TimeUnit.SECONDS);

        Recorder late = new Recorder();
        connect("late", late);

        assertEquals(List.of("ACME"), late.symbols);
        assertEquals(1, late.trades.size());
        assertEquals(offer, late.trades.getFirst().sellOrderId());
        assertEquals(1, late.orders.size(), "only the still open offer is part of the snapshot");
    }

    @Test
    void clientCannotModifyAnotherClientsOrder() throws Exception {
        ExchangeClient alice = connect("alice", new Recorder());
        ExchangeClient mallory = connect("mallory", new Recorder());
        long order = alice.sell("ACME", 10, TEN).get(5, TimeUnit.SECONDS);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> mallory.modify(order, 1, new BigDecimal("0.01")).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ExchangeException.class, e.getCause());
        assertEquals(10, exchange.getOrder(order).quantity());
    }

    @Test
    void requestsBeforeLoginAndMalformedRequestsAreRejected() throws Exception {
        ExchangeClient anonymous = new ExchangeClient("localhost", server.port(), new Recorder());
        clients.add(anonymous);
        assertErr(anonymous, "BUY ACME 1 10", "LOGIN first");
        anonymous.login("eve").get(5, TimeUnit.SECONDS);
        assertErr(anonymous, "BUY ACME ten 10", "ten");
        assertErr(anonymous, "BUY ACME 1", "argument");
        assertErr(anonymous, "FOO", "Unknown command");
    }

    @Test
    void serverLogsEveryRequest() throws Exception {
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(ClientSession.class.getName());
        logger.addHandler(capture);
        try {
            ExchangeClient client = connect("logger_test", new Recorder());
            client.sell("ACME", 3, TEN).get(5, TimeUnit.SECONDS);
            client.send("NONSENSE").exceptionally(t -> null).get(5, TimeUnit.SECONDS);

            assertTrue(logged.stream().anyMatch(m -> m.contains("[logger_test@") && m.contains("SELL ACME 3 10.00")
                    && m.contains("OK ORDER")));
            assertTrue(logged.stream().anyMatch(m -> m.contains("NONSENSE") && m.contains("ERR")));
            assertTrue(logged.stream().anyMatch(m -> m.contains("LOGIN logger_test")));
        } finally {
            logger.removeHandler(capture);
        }
    }

    private static void assertErr(ExchangeClient client, String command, String expectedFragment) throws Exception {
        try {
            client.send(command).get(5, TimeUnit.SECONDS);
            fail("expected an error for " + command);
        } catch (ExecutionException e) {
            assertTrue(e.getCause().getMessage().contains(expectedFragment),
                    "unexpected error: " + e.getCause().getMessage());
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met in time");
            }
            Thread.sleep(5);
        }
    }
}
