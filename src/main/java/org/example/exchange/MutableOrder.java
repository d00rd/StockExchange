package org.example.exchange;

import java.math.BigDecimal;

/** Internal order state; only mutated while holding the lock of the owning {@link OrderBook}. */
final class MutableOrder {
    final long id;
    final String trader;
    final String symbol;
    final Side side;
    BigDecimal price;
    int remaining;
    int filled;
    long sequence;
    OrderStatus status = OrderStatus.OPEN;

    MutableOrder(long id, String trader, String symbol, Side side, int quantity, BigDecimal price) {
        this.id = id;
        this.trader = trader;
        this.symbol = symbol;
        this.side = side;
        this.remaining = quantity;
        this.price = price;
    }

    void fill(int shares) {
        remaining -= shares;
        filled += shares;
        if (remaining == 0) {
            status = OrderStatus.FILLED;
        }
    }

    Order snapshot() {
        return new Order(id, trader, symbol, side, remaining, filled, price, status);
    }
}
