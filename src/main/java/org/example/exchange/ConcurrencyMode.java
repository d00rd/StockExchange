package org.example.exchange;

/**
 * Selects how {@link StockExchange#buyFromOffer} synchronizes. Only that operation is affected; regular orders are
 * always handled safely.
 */
public enum ConcurrencyMode {
    /** Checks the offer (price, remaining shares) and buys from it as one step while holding the book lock. */
    SAFE,
    /**
     * Intentionally broken: checks the offer without the lock and only locks for the update, so other threads can
     * change the offer in between (check-then-act race). Kept for comparison and tests.
     */
    UNSAFE
}
