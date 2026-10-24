package org.example.account;

import org.example.exchange.ExchangeException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe account storing a trader's cash balance and share holdings.
 * Follows standard banking double-entry reservation:
 * <ul>
 *   <li>{@code cash}: total ledger cash balance</li>
 *   <li>{@code reservedCash}: cash committed to resting buy orders</li>
 *   <li>{@code availableCash = cash - reservedCash}</li>
 *   <li>{@code holdings}: total shares owned</li>
 *   <li>{@code reservedHoldings}: shares committed to resting sell orders</li>
 *   <li>{@code availableShares = holdings - reservedHoldings}</li>
 * </ul>
 */
public class Account {
    public final String trader;
    /** Fair lock protecting all mutations and reads on this account's balances. */
    public final ReentrantLock lock = new ReentrantLock(true);

    private BigDecimal cash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private BigDecimal reservedCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private final Map<String, Integer> holdings = new HashMap<>();
    private final Map<String, Integer> reservedHoldings = new HashMap<>();

    public Account(String trader) {
        this.trader = Objects.requireNonNull(trader, "trader");
    }

    public Account(String trader, BigDecimal initialCash, Map<String, Integer> initialHoldings) {
        this(trader);
        if (initialCash != null) {
            deposit(initialCash);
        }
        if (initialHoldings != null) {
            initialHoldings.forEach(this::depositShares);
        }
    }

    /** Deposits cash into the account. */
    public void deposit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ExchangeException("Deposit amount must be positive");
        }
        BigDecimal normalized = amount.stripTrailingZeros().setScale(2, RoundingMode.UNNECESSARY);
        lock.lock();
        try {
            cash = cash.add(normalized);
        } finally {
            lock.unlock();
        }
    }

    /** Deposits shares for a symbol into the account. */
    public void depositShares(String symbol, int count) {
        if (symbol == null || symbol.isBlank()) {
            throw new ExchangeException("Symbol cannot be null or blank");
        }
        if (count < 0) {
            throw new ExchangeException("Share count cannot be negative");
        }
        if (count == 0) {
            return;
        }
        lock.lock();
        try {
            holdings.merge(symbol, count, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves cash for an outstanding buy order under {@link #lock}.
     *
     * @param amount cash amount to reserve (price * quantity)
     * @throws ExchangeException if available cash is insufficient
     */
    public void reserveCashSafely(BigDecimal amount) {
        lock.lock();
        try {
            checkAndReserveCash(amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * UNSAFE ON PURPOSE for concurrency demonstration.
     * Checks available cash without the lock, introduces a window for race conditions,
     * then locks to apply the reservation. Two concurrent requests can both observe
     * enough available funds and cause an account overdraft (double-spending).
     */
    public void reserveCashUnsafely(BigDecimal amount, long delayMs) {
        BigDecimal available = availableCash();
        if (available.compareTo(amount) < 0) {
            throw new ExchangeException("Insufficient funds for trader " + trader + ": required "
                    + amount + ", available " + available);
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lock.lock();
        try {
            reservedCash = reservedCash.add(amount);
        } finally {
            lock.unlock();
        }
    }

    private void checkAndReserveCash(BigDecimal amount) {
        BigDecimal available = cash.subtract(reservedCash);
        if (available.compareTo(amount) < 0) {
            throw new ExchangeException("Insufficient funds for trader " + trader + ": required "
                    + amount + ", available " + available);
        }
        reservedCash = reservedCash.add(amount);
    }

    /** Releases previously reserved cash (e.g. on order cancellation). Must be called under {@link #lock}. */
    public void releaseReservedCash(BigDecimal amount) {
        reservedCash = reservedCash.subtract(amount);
        if (reservedCash.signum() < 0) {
            reservedCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * Reserves shares for an outstanding sell offer under {@link #lock}.
     *
     * @param symbol company symbol
     * @param count  number of shares to reserve
     */
    public void reserveSharesSafely(String symbol, int count) {
        lock.lock();
        try {
            int owned = holdings.getOrDefault(symbol, 0);
            int reserved = reservedHoldings.getOrDefault(symbol, 0);
            int available = owned - reserved;
            if (available < count) {
                throw new ExchangeException("Insufficient shares of " + symbol + " for trader " + trader
                        + ": required " + count + ", available " + available);
            }
            reservedHoldings.put(symbol, reserved + count);
        } finally {
            lock.unlock();
        }
    }

    /** Releases previously reserved shares (e.g. on order cancellation). Must be called under {@link #lock}. */
    public void releaseReservedShares(String symbol, int count) {
        int reserved = reservedHoldings.getOrDefault(symbol, 0);
        int updated = Math.max(0, reserved - count);
        if (updated == 0) {
            reservedHoldings.remove(symbol);
        } else {
            reservedHoldings.put(symbol, updated);
        }
    }

    /**
     * Completes a trade from the buyer's perspective.
     * Deducts traded cash from total {@code cash}, releases {@code reservedAmount} from {@code reservedCash},
     * and adds acquired shares to {@code holdings}.
     * Must be called while holding {@link #lock}.
     */
    public void applyBuyerFill(String symbol, int shares, BigDecimal tradeCost, BigDecimal reservedCost) {
        cash = cash.subtract(tradeCost);
        reservedCash = reservedCash.subtract(reservedCost);
        if (reservedCash.signum() < 0) {
            reservedCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        holdings.merge(symbol, shares, Integer::sum);
    }

    /**
     * Completes a trade from the seller's perspective.
     * Adds proceeds to total {@code cash}, deducts shares from {@code holdings},
     * and releases shares from {@code reservedHoldings}.
     * Must be called while holding {@link #lock}.
     */
    public void applySellerFill(String symbol, int shares, BigDecimal proceeds) {
        cash = cash.add(proceeds);
        int total = holdings.getOrDefault(symbol, 0) - shares;
        if (total <= 0) {
            holdings.remove(symbol);
        } else {
            holdings.put(symbol, total);
        }

        int reserved = reservedHoldings.getOrDefault(symbol, 0) - shares;
        if (reserved <= 0) {
            reservedHoldings.remove(symbol);
        } else {
            reservedHoldings.put(symbol, reserved);
        }
    }

    /** Adjusts buy reservation when modifying an order. Must be called under {@link #lock}. */
    public void adjustBuyReservation(BigDecimal delta) {
        if (delta.signum() > 0) {
            checkAndReserveCash(delta);
        } else if (delta.signum() < 0) {
            releaseReservedCash(delta.abs());
        }
    }

    /** Adjusts sell reservation when modifying an order. Must be called under {@link #lock}. */
    public void adjustSellReservation(String symbol, int deltaShares) {
        if (deltaShares > 0) {
            int owned = holdings.getOrDefault(symbol, 0);
            int reserved = reservedHoldings.getOrDefault(symbol, 0);
            int available = owned - reserved;
            if (available < deltaShares) {
                throw new ExchangeException("Insufficient shares of " + symbol + " to increase offer: required "
                        + deltaShares + ", available " + available);
            }
            reservedHoldings.put(symbol, reserved + deltaShares);
        } else if (deltaShares < 0) {
            releaseReservedShares(symbol, -deltaShares);
        }
    }

    public BigDecimal cash() {
        lock.lock();
        try {
            return cash;
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal reservedCash() {
        lock.lock();
        try {
            return reservedCash;
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal availableCash() {
        lock.lock();
        try {
            return cash.subtract(reservedCash);
        } finally {
            lock.unlock();
        }
    }

    public AccountSnapshot snapshot() {
        lock.lock();
        try {
            Map<String, Integer> available = new HashMap<>();
            holdings.forEach((sym, total) -> {
                int res = reservedHoldings.getOrDefault(sym, 0);
                available.put(sym, total - res);
            });
            return new AccountSnapshot(
                    trader,
                    cash.subtract(reservedCash), // available cash
                    reservedCash,
                    available,
                    reservedHoldings
            );
        } finally {
            lock.unlock();
        }
    }
}
