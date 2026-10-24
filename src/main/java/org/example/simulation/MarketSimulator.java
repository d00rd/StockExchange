package org.example.simulation;

import org.example.account.AccountManager;
import org.example.account.AccountManager.InvariantAuditResult;
import org.example.exchange.ConcurrencyMode;
import org.example.exchange.ExchangeException;
import org.example.exchange.Order;
import org.example.exchange.Side;
import org.example.exchange.StockExchange;
import org.example.exchange.Trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-threaded market simulator where concurrent client threads arrive dynamically, place orders,
 * execute trades, modify/cancel orders, and take offers, while an auditor continuously verifies
 * system-wide financial and order book invariants.
 */
public class MarketSimulator {
    public static final List<String> DEFAULT_SYMBOLS = List.of("AAPL", "MSFT", "GOOG", "AMZN", "TSLA");
    public static final BigDecimal TRADER_INITIAL_CASH = new BigDecimal("500000.00");
    public static final int TRADER_INITIAL_SHARES = 5_000;

    private final StockExchange exchange;
    private final int clientCount;
    private final Duration duration;
    private final List<String> symbols;

    // Metrics counters
    private final AtomicLong totalOperations = new AtomicLong();
    private final AtomicLong buyOrdersPlaced = new AtomicLong();
    private final AtomicLong sellOrdersPlaced = new AtomicLong();
    private final AtomicLong takesAttempted = new AtomicLong();
    private final AtomicLong modificationsAttempted = new AtomicLong();
    private final AtomicLong cancelsAttempted = new AtomicLong();

    private final List<String> runtimeViolations = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MarketSimulator(StockExchange exchange, int clientCount, Duration duration) {
        this(exchange, clientCount, duration, DEFAULT_SYMBOLS);
    }

    public MarketSimulator(StockExchange exchange, int clientCount, Duration duration, List<String> symbols) {
        if (clientCount <= 0) {
            throw new IllegalArgumentException("clientCount must be positive");
        }
        this.exchange = exchange;
        this.clientCount = clientCount;
        this.duration = duration != null ? duration : Duration.ofSeconds(3);
        this.symbols = List.copyOf(symbols);
    }

    public SimulationReport runSimulation() throws InterruptedException {
        // 1. Ensure symbols are listed
        for (String sym : symbols) {
            exchange.listCompany(sym, sym + " Corporation");
        }

        // 2. Provision dedicated simulated trader accounts
        List<String> traders = new ArrayList<>();
        AccountManager am = exchange.accountManager();
        for (int i = 0; i < clientCount; i++) {
            String name = "trader_" + i;
            traders.add(name);
            Map<String, Integer> initialHoldings = new java.util.HashMap<>();
            for (String sym : symbols) {
                initialHoldings.put(sym, TRADER_INITIAL_SHARES);
            }
            am.createAccount(name, TRADER_INITIAL_CASH, initialHoldings);
        }

        // 3. Initial system invariant snapshot
        InvariantAuditResult initialAudit = am.auditInvariants(exchange.openOrders());

        running.set(true);
        CyclicBarrier startBarrier = new CyclicBarrier(clientCount + 1);
        CountDownLatch finishLatch = new CountDownLatch(clientCount);
        long startTime = System.currentTimeMillis();

        // 4. Background Invariant Auditor thread (monitors balance conservation and non-negativity in real-time)
        Thread auditor = Thread.ofPlatform().name("market-auditor").start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(100);
                    InvariantAuditResult audit = am.auditBalances();
                    if (!audit.passed()) {
                        runtimeViolations.addAll(audit.violations());
                    }
                    if (initialAudit.totalCash().compareTo(audit.totalCash()) != 0) {
                        runtimeViolations.add("Cash conservation violated mid-flight: expected "
                                + initialAudit.totalCash() + " but observed " + audit.totalCash());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    runtimeViolations.add("Auditor exception: " + e.getMessage());
                }
            }
        });

        // 5. Spawn concurrent client threads
        for (int i = 0; i < clientCount; i++) {
            final String trader = traders.get(i);
            Thread.ofPlatform().name("client-" + trader).start(() -> {
                try {
                    startBarrier.await();
                    runClientLoop(trader);
                } catch (Exception e) {
                    runtimeViolations.add("Client " + trader + " failed: " + e.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Release all client threads simultaneously
        try {
            startBarrier.await();
        } catch (Exception e) {
            throw new RuntimeException("Simulation barrier failed", e);
        }

        // Run for the designated duration
        Thread.sleep(duration.toMillis());
        running.set(false);

        // Wait for all client threads to conclude
        finishLatch.await(5, TimeUnit.SECONDS);
        auditor.interrupt();
        auditor.join(1000);

        long actualDuration = System.currentTimeMillis() - startTime;

        // 6. Final Comprehensive Invariant Audit (including open orders)
        InvariantAuditResult finalAudit = am.auditInvariants(exchange.openOrders());

        // 7. Calculate aggregate trade metrics
        List<Trade> trades = exchange.trades();
        BigDecimal totalVolume = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (Trade t : trades) {
            totalVolume = totalVolume.add(t.price().multiply(BigDecimal.valueOf(t.quantity())));
        }

        double durationSec = actualDuration / 1000.0;
        long ops = totalOperations.get();
        double opsPerSec = durationSec > 0 ? ops / durationSec : 0;
        double tradesPerSec = durationSec > 0 ? trades.size() / durationSec : 0;

        return new SimulationReport(
                clientCount,
                actualDuration,
                ops,
                buyOrdersPlaced.get(),
                sellOrdersPlaced.get(),
                takesAttempted.get(),
                modificationsAttempted.get(),
                cancelsAttempted.get(),
                trades.size(),
                totalVolume,
                opsPerSec,
                tradesPerSec,
                initialAudit,
                finalAudit,
                List.copyOf(runtimeViolations)
        );
    }

    private void runClientLoop(String trader) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Long> ownOpenOrders = new ArrayList<>();

        while (running.get()) {
            String symbol = symbols.get(rnd.nextInt(symbols.size()));
            int actionRoll = rnd.nextInt(100);

            try {
                if (actionRoll < 35) {
                    // 35% Limit BUY
                    int qty = rnd.nextInt(5, 50);
                    BigDecimal price = BigDecimal.valueOf(rnd.nextDouble(90.00, 110.00)).setScale(2, RoundingMode.HALF_UP);
                    Order o = exchange.placeOrder(trader, symbol, Side.BUY, qty, price);
                    buyOrdersPlaced.incrementAndGet();
                    totalOperations.incrementAndGet();
                    if (o.status() == org.example.exchange.OrderStatus.OPEN) {
                        ownOpenOrders.add(o.id());
                    }
                } else if (actionRoll < 70) {
                    // 35% Limit SELL
                    int qty = rnd.nextInt(5, 50);
                    BigDecimal price = BigDecimal.valueOf(rnd.nextDouble(90.00, 110.00)).setScale(2, RoundingMode.HALF_UP);
                    Order o = exchange.placeOrder(trader, symbol, Side.SELL, qty, price);
                    sellOrdersPlaced.incrementAndGet();
                    totalOperations.incrementAndGet();
                    if (o.status() == org.example.exchange.OrderStatus.OPEN) {
                        ownOpenOrders.add(o.id());
                    }
                } else if (actionRoll < 85) {
                    // 15% TAKE (Buy directly from open sell offer)
                    takesAttempted.incrementAndGet();
                    totalOperations.incrementAndGet();
                    List<Order> open = exchange.openOrders(symbol);
                    List<Order> offers = open.stream()
                            .filter(o -> o.side() == Side.SELL && !o.trader().equals(trader))
                            .toList();
                    if (!offers.isEmpty()) {
                        Order target = offers.get(rnd.nextInt(offers.size()));
                        int takeQty = Math.max(1, target.quantity() / 2);
                        exchange.buyFromOffer(trader, target.id(), takeQty, target.price());
                    }
                } else if (actionRoll < 95 && !ownOpenOrders.isEmpty()) {
                    // 10% Modify own open order
                    modificationsAttempted.incrementAndGet();
                    totalOperations.incrementAndGet();
                    long orderId = ownOpenOrders.get(rnd.nextInt(ownOpenOrders.size()));
                    int newQty = rnd.nextInt(5, 40);
                    BigDecimal newPrice = BigDecimal.valueOf(rnd.nextDouble(90.00, 110.00)).setScale(2, RoundingMode.HALF_UP);
                    try {
                        exchange.modifyOrder(trader, orderId, newQty, newPrice);
                    } catch (ExchangeException e) {
                        ownOpenOrders.remove(orderId);
                    }
                } else if (!ownOpenOrders.isEmpty()) {
                    // 5% Cancel own open order
                    cancelsAttempted.incrementAndGet();
                    totalOperations.incrementAndGet();
                    long orderId = ownOpenOrders.remove(rnd.nextInt(ownOpenOrders.size()));
                    try {
                        exchange.cancelOrder(trader, orderId);
                    } catch (ExchangeException ignored) {
                        // Already filled or cancelled
                    }
                }
            } catch (ExchangeException ignored) {
                // Legitimate exchange rejection (e.g. outbid, already filled, transaction in progress)
            }

            // Realistic client think-time between operations (1 to 4 ms)
            try {
                Thread.sleep(rnd.nextInt(1, 4));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int clients = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        ConcurrencyMode mode = args.length > 2
                ? ConcurrencyMode.valueOf(args[2].toUpperCase(Locale.ROOT))
                : ConcurrencyMode.SAFE;
        Duration delay = Duration.ofMillis(args.length > 3 ? Long.parseLong(args[3]) : 0);

        System.out.printf("Starting Market Simulator: %d concurrent client threads for %d seconds (mode: %s, delay: %s)...%n",
                clients, seconds, mode, delay);

        StockExchange exchange = new StockExchange(delay, Duration.ofSeconds(2), mode);
        MarketSimulator simulator = new MarketSimulator(exchange, clients, Duration.ofSeconds(seconds));
        SimulationReport report = simulator.runSimulation();
        report.printSummary();
    }
}
