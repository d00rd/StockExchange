package org.example.exchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Thread-safe matching engine. Every company has its own lock, so trading different companies proceeds in parallel
 * while all operations on the same company's shares are serialized.
 */
public class StockExchange {
    public static final int MAX_QUANTITY = 1_000_000;
    public static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    private static final Logger LOG = Logger.getLogger(StockExchange.class.getName());
    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9]{0,9}");
    private static final Pattern TRADER = Pattern.compile("[A-Za-z0-9_]{1,20}");

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<Long, MutableOrder> orders = new ConcurrentHashMap<>();
    private final List<Trade> trades = Collections.synchronizedList(new ArrayList<>());
    private final List<ExchangeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong tradeIds = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final Duration settlementDelay;
    private final Duration modifyTimeout;

    public StockExchange() {
        this(Duration.ZERO, Duration.ofSeconds(2));
    }

    /**
     * @param settlementDelay simulated duration of a single transaction (shares/money transfer)
     * @param modifyTimeout   how long a modification/cancel waits for an ongoing transaction before being rejected
     */
    public StockExchange(Duration settlementDelay, Duration modifyTimeout) {
        this.settlementDelay = Objects.requireNonNull(settlementDelay);
        this.modifyTimeout = Objects.requireNonNull(modifyTimeout);
    }

    public void listCompany(String symbol, String name) {
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
            throw new ExchangeException("Invalid symbol: " + symbol);
        }
        books.putIfAbsent(symbol, new OrderBook(symbol, name));
    }

    public static void validateTrader(String trader) {
        if (trader == null || !TRADER.matcher(trader).matches()) {
            throw new ExchangeException("Trader name must be 1-20 letters, digits or '_'");
        }
    }

    public Order placeOrder(String trader, String symbol, Side side, int quantity, BigDecimal price) {
        validateTrader(trader);
        Objects.requireNonNull(side, "side");
        validateQuantity(quantity);
        BigDecimal limit = validatePrice(price);
        OrderBook book = book(symbol);
        MutableOrder order = new MutableOrder(orderIds.incrementAndGet(), trader, book.symbol, side, quantity, limit);
        book.lock.lock();
        try {
            order.sequence = sequence.incrementAndGet();
            orders.put(order.id, order);
            book.add(order);
            fireOrder(order.snapshot());
            match(book);
            return order.snapshot();
        } finally {
            book.lock.unlock();
        }
    }

    /**
     * Changes quantity (remaining shares) and price of an open order. Rejected if a transaction on the same
     * company's shares does not finish within the configured timeout.
     */
    public Order modifyOrder(String trader, long orderId, int quantity, BigDecimal price) {
        validateQuantity(quantity);
        BigDecimal limit = validatePrice(price);
        MutableOrder order = ownedOrder(trader, orderId);
        OrderBook book = books.get(order.symbol);
        lockForModification(book);
        try {
            requireOpen(order);
            boolean losesPriority = limit.compareTo(order.price) != 0 || quantity > order.remaining;
            book.remove(order);
            order.price = limit;
            order.remaining = quantity;
            if (losesPriority) {
                order.sequence = sequence.incrementAndGet();
            }
            book.add(order);
            fireOrder(order.snapshot());
            match(book);
            return order.snapshot();
        } finally {
            book.lock.unlock();
        }
    }

    public Order cancelOrder(String trader, long orderId) {
        MutableOrder order = ownedOrder(trader, orderId);
        OrderBook book = books.get(order.symbol);
        lockForModification(book);
        try {
            requireOpen(order);
            book.remove(order);
            order.status = OrderStatus.CANCELLED;
            fireOrder(order.snapshot());
            return order.snapshot();
        } finally {
            book.lock.unlock();
        }
    }

    public Order getOrder(long orderId) {
        MutableOrder order = orders.get(orderId);
        if (order == null) {
            throw new ExchangeException("Unknown order " + orderId);
        }
        OrderBook book = books.get(order.symbol);
        book.lock.lock();
        try {
            return order.snapshot();
        } finally {
            book.lock.unlock();
        }
    }

    public Map<String, String> companies() {
        Map<String, String> result = new LinkedHashMap<>();
        sortedBooks().forEach(b -> result.put(b.symbol, b.companyName));
        return result;
    }

    public List<Order> openOrders(String symbol) {
        OrderBook book = book(symbol);
        List<Order> result = new ArrayList<>();
        book.lock.lock();
        try {
            book.collectOpen(result);
        } finally {
            book.lock.unlock();
        }
        return result;
    }

    public List<Order> openOrders() {
        List<Order> result = new ArrayList<>();
        for (OrderBook book : sortedBooks()) {
            result.addAll(openOrders(book.symbol));
        }
        return result;
    }

    public List<Trade> trades() {
        synchronized (trades) {
            return List.copyOf(trades);
        }
    }

    public boolean isTransactionInProgress(String symbol) {
        return book(symbol).lock.isLocked();
    }

    /** Delivers a consistent snapshot to the listener and then registers it for live events, with no gap. */
    public void subscribe(ExchangeListener listener) {
        List<OrderBook> all = sortedBooks();
        all.forEach(b -> b.lock.lock());
        try {
            Map<String, String> companies = new LinkedHashMap<>();
            List<Order> open = new ArrayList<>();
            for (OrderBook book : all) {
                companies.put(book.symbol, book.companyName);
                book.collectOpen(open);
            }
            listener.onSnapshot(companies, open, trades());
            listeners.add(listener);
        } finally {
            all.reversed().forEach(b -> b.lock.unlock());
        }
    }

    public void unsubscribe(ExchangeListener listener) {
        listeners.remove(listener);
    }

    /** Must be called while holding {@code book.lock}. */
    private void match(OrderBook book) {
        while (!book.bids.isEmpty() && !book.asks.isEmpty()) {
            MutableOrder bid = book.bids.getFirst();
            MutableOrder ask = book.asks.getFirst();
            if (bid.price.compareTo(ask.price) < 0) {
                return;
            }
            int shares = Math.min(bid.remaining, ask.remaining);
            // The order that was waiting in the book sets the price.
            BigDecimal price = bid.sequence < ask.sequence ? bid.price : ask.price;
            settle();
            bid.fill(shares);
            ask.fill(shares);
            Trade trade = new Trade(tradeIds.incrementAndGet(), book.symbol, bid.trader, ask.trader,
                    bid.id, ask.id, shares, price, System.currentTimeMillis());
            trades.add(trade);
            if (bid.remaining == 0) {
                book.bids.removeFirst();
            }
            if (ask.remaining == 0) {
                book.asks.removeFirst();
            }
            fireTrade(trade);
            fireOrder(bid.snapshot());
            fireOrder(ask.snapshot());
        }
    }

    private void settle() {
        if (settlementDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(settlementDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void lockForModification(OrderBook book) {
        try {
            if (!book.lock.tryLock(modifyTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ExchangeException("Transaction in progress for " + book.symbol + ", try again later");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExchangeException("Interrupted while waiting for " + book.symbol);
        }
    }

    private MutableOrder ownedOrder(String trader, long orderId) {
        MutableOrder order = orders.get(orderId);
        if (order == null) {
            throw new ExchangeException("Unknown order " + orderId);
        }
        if (!order.trader.equals(trader)) {
            throw new ExchangeException("Order " + orderId + " does not belong to " + trader);
        }
        return order;
    }

    private static void requireOpen(MutableOrder order) {
        if (order.status != OrderStatus.OPEN) {
            throw new ExchangeException("Order " + order.id + " is already " + order.status);
        }
    }

    private OrderBook book(String symbol) {
        OrderBook book = symbol == null ? null : books.get(symbol);
        if (book == null) {
            throw new ExchangeException("Unknown symbol " + symbol);
        }
        return book;
    }

    private List<OrderBook> sortedBooks() {
        return books.values().stream().sorted(Comparator.comparing(b -> b.symbol)).toList();
    }

    private static void validateQuantity(int quantity) {
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new ExchangeException("Quantity must be between 1 and " + MAX_QUANTITY);
        }
    }

    private static BigDecimal validatePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new ExchangeException("Price must be positive, at most " + MAX_PRICE + ", with 2 decimals");
        }
        return price.setScale(2);
    }

    private void fireOrder(Order order) {
        for (ExchangeListener l : listeners) {
            try {
                l.onOrder(order);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed on order event", e);
            }
        }
    }

    private void fireTrade(Trade trade) {
        for (ExchangeListener l : listeners) {
            try {
                l.onTrade(trade);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed on trade event", e);
            }
        }
    }
}
