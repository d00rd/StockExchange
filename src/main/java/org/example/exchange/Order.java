package org.example.exchange;

import java.math.BigDecimal;

/**
 * Immutable snapshot of an order. {@code quantity} is the number of shares still waiting to be traded.
 */
public record Order(long id, String trader, String symbol, Side side, int quantity, int filled,
                    BigDecimal price, OrderStatus status) {
    /** Alias for quantity: number of shares still waiting to be traded. */
    public int remaining() {
        return quantity;
    }
}
