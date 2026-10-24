package org.example.account;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Immutable point-in-time snapshot of a trader's financial account.
 *
 * @param trader           the unique trader identifier
 * @param cash             available (liquid) cash balance
 * @param reservedCash     cash reserved for outstanding buy orders
 * @param holdings         map of symbol -> available shares owned
 * @param reservedHoldings map of symbol -> shares reserved for outstanding sell orders
 */
public record AccountSnapshot(
        String trader,
        BigDecimal cash,
        BigDecimal reservedCash,
        Map<String, Integer> holdings,
        Map<String, Integer> reservedHoldings
) {
    public AccountSnapshot {
        holdings = Map.copyOf(holdings);
        reservedHoldings = Map.copyOf(reservedHoldings);
    }

    /** Total cash value held in the account (available + reserved). */
    public BigDecimal totalCash() {
        return cash.add(reservedCash);
    }

    /** Total shares owned for a given symbol (available + reserved). */
    public int totalShares(String symbol) {
        return holdings.getOrDefault(symbol, 0) + reservedHoldings.getOrDefault(symbol, 0);
    }

    /** Available shares for trading for a given symbol. */
    public int availableShares(String symbol) {
        return holdings.getOrDefault(symbol, 0);
    }
}
