package org.example.exchange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Open requests (bids) and offers (asks) for one company, kept in price-time priority. */
final class OrderBook {
    private static final Comparator<MutableOrder> BID_PRIORITY =
            Comparator.comparing((MutableOrder o) -> o.price).reversed().thenComparingLong(o -> o.sequence);
    private static final Comparator<MutableOrder> ASK_PRIORITY =
            Comparator.comparing((MutableOrder o) -> o.price).thenComparingLong(o -> o.sequence);

    final String symbol;
    final String companyName;
    /** Held for the full duration of every transaction on this company's shares. */
    final ReentrantLock lock = new ReentrantLock(true);
    final List<MutableOrder> bids = new ArrayList<>();
    final List<MutableOrder> asks = new ArrayList<>();

    OrderBook(String symbol, String companyName) {
        this.symbol = symbol;
        this.companyName = companyName;
    }

    void add(MutableOrder order) {
        List<MutableOrder> list = order.side == Side.BUY ? bids : asks;
        list.add(order);
        list.sort(order.side == Side.BUY ? BID_PRIORITY : ASK_PRIORITY);
    }

    void remove(MutableOrder order) {
        (order.side == Side.BUY ? bids : asks).remove(order);
    }

    void collectOpen(List<Order> target) {
        bids.forEach(o -> target.add(o.snapshot()));
        asks.forEach(o -> target.add(o.snapshot()));
    }
}
