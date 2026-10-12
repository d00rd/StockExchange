package org.example.exchange;

import java.util.List;
import java.util.Map;

/**
 * Receives exchange events. Callbacks run while the order book lock is held, so they must be fast and non-blocking.
 */
public interface ExchangeListener {

    /** Consistent view of the exchange delivered once, right before live events start flowing. */
    default void onSnapshot(Map<String, String> companies, List<Order> openOrders, List<Trade> trades) {
    }

    default void onOrder(Order order) {
    }

    default void onTrade(Trade trade) {
    }
}
