package org.example.protocol;

import org.example.exchange.Order;
import org.example.exchange.OrderStatus;
import org.example.exchange.Side;
import org.example.exchange.Trade;

import java.math.BigDecimal;

/**
 * Line based text protocol shared by server and client.
 * <pre>
 * Client -> server: LOGIN name | BUY sym qty price | SELL sym qty price | MODIFY id qty price | CANCEL id
 *                   TAKE offerId qty maxPrice   (buy directly from one sell offer, paying at most maxPrice)
 *                   ORDERS | TRADES | SYMBOLS | QUIT
 * Server -> client: OK text | ERR text   (one per request, in request order)
 *                   SYMBOL sym name | ORDER ... | TRADE ...   (snapshots and live events)
 * </pre>
 */
public final class Protocol {
    public static final int DEFAULT_PORT = 5000;

    private Protocol() {
    }

    public static String order(Order o) {
        return String.join(" ", "ORDER", Long.toString(o.id()), o.trader(), o.symbol(), o.side().name(),
                Integer.toString(o.quantity()), Integer.toString(o.filled()), o.price().toPlainString(),
                o.status().name());
    }

    public static Order parseOrder(String line) {
        String[] p = split(line, "ORDER", 9);
        return new Order(Long.parseLong(p[1]), p[2], p[3], Side.valueOf(p[4]), Integer.parseInt(p[5]),
                Integer.parseInt(p[6]), new BigDecimal(p[7]), OrderStatus.valueOf(p[8]));
    }

    public static String trade(Trade t) {
        return String.join(" ", "TRADE", Long.toString(t.id()), t.symbol(), t.buyer(), t.seller(),
                Long.toString(t.buyOrderId()), Long.toString(t.sellOrderId()), Integer.toString(t.quantity()),
                t.price().toPlainString(), Long.toString(t.timestamp()));
    }

    public static Trade parseTrade(String line) {
        String[] p = split(line, "TRADE", 10);
        return new Trade(Long.parseLong(p[1]), p[2], p[3], p[4], Long.parseLong(p[5]), Long.parseLong(p[6]),
                Integer.parseInt(p[7]), new BigDecimal(p[8]), Long.parseLong(p[9]));
    }

    public static String symbol(String symbol, String companyName) {
        return "SYMBOL " + symbol + " " + companyName;
    }

    private static String[] split(String line, String type, int fields) {
        String[] p = line.split(" ");
        if (p.length != fields || !p[0].equals(type)) {
            throw new IllegalArgumentException("Malformed " + type + " message: " + line);
        }
        return p;
    }
}
