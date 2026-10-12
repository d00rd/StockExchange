package org.example.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockExchangeTest {
    private StockExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = new StockExchange();
        exchange.listCompany("ACME", "Acme Corp");
    }

    private static BigDecimal p(String value) {
        return new BigDecimal(value);
    }

    @Test
    void matchingOfferAndRequestTradeMinimumQuantity() {
        Order sell = exchange.placeOrder("seller", "ACME", Side.SELL, 100, p("10.00"));
        Order buy = exchange.placeOrder("buyer", "ACME", Side.BUY, 40, p("10.00"));

        assertEquals(OrderStatus.FILLED, buy.status());
        assertEquals(40, buy.filled());
        Order rest = exchange.getOrder(sell.id());
        assertEquals(60, rest.quantity());
        assertEquals(OrderStatus.OPEN, rest.status());

        List<Trade> trades = exchange.trades();
        assertEquals(1, trades.size());
        assertEquals(40, trades.getFirst().quantity());
        assertEquals("buyer", trades.getFirst().buyer());
        assertEquals("seller", trades.getFirst().seller());
    }

    @Test
    void noTradeWhenPricesDoNotMatch() {
        exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("11.00"));
        exchange.placeOrder("buyer", "ACME", Side.BUY, 10, p("10.99"));

        assertTrue(exchange.trades().isEmpty());
        assertEquals(2, exchange.openOrders("ACME").size());
    }

    @Test
    void tradeExecutesAtPriceOfTheWaitingOrder() {
        exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("10.00"));
        exchange.placeOrder("buyer", "ACME", Side.BUY, 10, p("12.00"));

        assertEquals(0, p("10.00").compareTo(exchange.trades().getFirst().price()));
    }

    @Test
    void bestPriceThenOldestOrderIsMatchedFirst() {
        exchange.placeOrder("s1", "ACME", Side.SELL, 10, p("10.50"));
        exchange.placeOrder("s2", "ACME", Side.SELL, 10, p("10.00"));
        exchange.placeOrder("s3", "ACME", Side.SELL, 10, p("10.00"));

        exchange.placeOrder("buyer", "ACME", Side.BUY, 25, p("11.00"));

        List<Trade> trades = exchange.trades();
        assertEquals(List.of("s2", "s3", "s1"), trades.stream().map(Trade::seller).toList());
        assertEquals(List.of(10, 10, 5), trades.stream().map(Trade::quantity).toList());
    }

    @Test
    void modifyingPriceCanTriggerATrade() {
        Order sell = exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("12.00"));
        exchange.placeOrder("buyer", "ACME", Side.BUY, 10, p("10.00"));
        assertTrue(exchange.trades().isEmpty());

        Order modified = exchange.modifyOrder("seller", sell.id(), 10, p("10.00"));

        assertEquals(OrderStatus.FILLED, modified.status());
        assertEquals(1, exchange.trades().size());
    }

    @Test
    void onlyTheOwnerCanModifyOrCancel() {
        Order sell = exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("12.00"));

        assertThrows(ExchangeException.class, () -> exchange.modifyOrder("mallory", sell.id(), 1, p("1.00")));
        assertThrows(ExchangeException.class, () -> exchange.cancelOrder("mallory", sell.id()));
    }

    @Test
    void cancelledOrFilledOrdersCannotBeChanged() {
        Order sell = exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("12.00"));
        assertEquals(OrderStatus.CANCELLED, exchange.cancelOrder("seller", sell.id()).status());
        assertTrue(exchange.openOrders("ACME").isEmpty());
        assertThrows(ExchangeException.class, () -> exchange.modifyOrder("seller", sell.id(), 5, p("12.00")));

        Order sell2 = exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("12.00"));
        exchange.placeOrder("buyer", "ACME", Side.BUY, 10, p("12.00"));
        assertThrows(ExchangeException.class, () -> exchange.cancelOrder("seller", sell2.id()));
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(ExchangeException.class, () -> exchange.placeOrder("a", "NOPE", Side.BUY, 1, p("1")));
        assertThrows(ExchangeException.class, () -> exchange.placeOrder("a", "ACME", Side.BUY, 0, p("1")));
        assertThrows(ExchangeException.class, () -> exchange.placeOrder("a", "ACME", Side.BUY, 1, p("-1")));
        assertThrows(ExchangeException.class, () -> exchange.placeOrder("a", "ACME", Side.BUY, 1, p("1.001")));
        assertThrows(ExchangeException.class, () -> exchange.placeOrder("bad name", "ACME", Side.BUY, 1, p("1")));
    }

    @Test
    void listenersReceiveOrderAndTradeEvents() {
        List<Object> events = new CopyOnWriteArrayList<>();
        exchange.subscribe(new ExchangeListener() {
            @Override
            public void onOrder(Order order) {
                events.add(order);
            }

            @Override
            public void onTrade(Trade trade) {
                events.add(trade);
            }
        });
        exchange.placeOrder("seller", "ACME", Side.SELL, 10, p("10.00"));
        exchange.placeOrder("buyer", "ACME", Side.BUY, 10, p("10.00"));

        // sell placed, buy placed, trade, buy filled, sell filled
        assertEquals(5, events.size());
        assertTrue(events.get(2) instanceof Trade);
    }
}
