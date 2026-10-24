package org.example.account;

import org.example.exchange.ConcurrencyMode;
import org.example.exchange.ExchangeException;
import org.example.exchange.Order;
import org.example.exchange.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all trader accounts, balance reservations, and bilateral trade settlements.
 *
 * <h3>Concurrency and Deadlock Prevention</h3>
 * When settling a trade between a buyer and a seller, both trader accounts must be updated atomically.
 * If Alice trades with Bob while Bob simultaneously trades with Alice, an arbitrary lock acquisition order
 * would produce a classic circular wait deadlock:
 * <pre>
 *   Thread 1: locks Alice, waits for Bob
 *   Thread 2: locks Bob, waits for Alice
 * </pre>
 * {@link #settleTrade} solves this by enforcing a canonical lock acquisition order: accounts are always locked
 * in lexicographical order of their unique trader identifier ({@code traderA.compareTo(traderB) < 0}).
 * This mathematically eliminates circular wait, guaranteeing deadlock-free bilateral settlements.
 */
public class AccountManager {
    public static final BigDecimal DEFAULT_INITIAL_CASH = new BigDecimal("10000000.00");
    public static final int DEFAULT_INITIAL_SHARES = 100_000;

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Set<String> knownSymbols = ConcurrentHashMap.newKeySet();
    private volatile boolean autoProvision = true;
    private final BigDecimal defaultCash;
    private final int defaultShares;

    public AccountManager() {
        this(true, DEFAULT_INITIAL_CASH, DEFAULT_INITIAL_SHARES);
    }

    public AccountManager(boolean autoProvision, BigDecimal defaultCash, int defaultShares) {
        this.autoProvision = autoProvision;
        this.defaultCash = defaultCash != null ? defaultCash : DEFAULT_INITIAL_CASH;
        this.defaultShares = defaultShares;
    }

    public void registerSymbol(String symbol) {
        if (symbol != null) {
            knownSymbols.add(symbol);
        }
    }

    public void setAutoProvision(boolean autoProvision) {
        this.autoProvision = autoProvision;
    }

    public boolean isAutoProvision() {
        return autoProvision;
    }

    public Account getAccount(String trader) {
        return accounts.get(trader);
    }

    public Account getOrCreateAccount(String trader) {
        Objects.requireNonNull(trader, "trader");
        return accounts.computeIfAbsent(trader, t -> {
            if (autoProvision) {
                Map<String, Integer> initialHoldings = new HashMap<>();
                knownSymbols.forEach(s -> initialHoldings.put(s, defaultShares));
                return new Account(t, defaultCash, initialHoldings);
            } else {
                return new Account(t);
            }
        });
    }

    public void createAccount(String trader, BigDecimal initialCash, Map<String, Integer> initialHoldings) {
        Objects.requireNonNull(trader, "trader");
        if (initialHoldings != null) {
            knownSymbols.addAll(initialHoldings.keySet());
        }
        accounts.put(trader, new Account(trader, initialCash, initialHoldings));
    }

    public void deposit(String trader, BigDecimal amount) {
        getOrCreateAccount(trader).deposit(amount);
    }

    public void reserveForBuy(String trader, int quantity, BigDecimal price, ConcurrencyMode mode, long delayMs) {
        Account account = getOrCreateAccount(trader);
        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
        if (mode == ConcurrencyMode.SAFE) {
            account.reserveCashSafely(totalCost);
        } else {
            account.reserveCashUnsafely(totalCost, delayMs);
        }
    }

    public void reserveForSell(String trader, String symbol, int quantity) {
        Account account = getOrCreateAccount(trader);
        account.reserveSharesSafely(symbol, quantity);
    }

    public void releaseBuyReservation(String trader, int quantity, BigDecimal price) {
        Account account = accounts.get(trader);
        if (account != null) {
            BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
            account.lock.lock();
            try {
                account.releaseReservedCash(totalCost);
            } finally {
                account.lock.unlock();
            }
        }
    }

    public void releaseSellReservation(String trader, String symbol, int quantity) {
        Account account = accounts.get(trader);
        if (account != null) {
            account.lock.lock();
            try {
                account.releaseReservedShares(symbol, quantity);
            } finally {
                account.lock.unlock();
            }
        }
    }

    public void adjustBuyReservation(String trader, BigDecimal delta) {
        Account account = accounts.get(trader);
        if (account != null) {
            account.lock.lock();
            try {
                account.adjustBuyReservation(delta);
            } finally {
                account.lock.unlock();
            }
        }
    }

    public void adjustSellReservation(String trader, String symbol, int deltaShares) {
        Account account = accounts.get(trader);
        if (account != null) {
            account.lock.lock();
            try {
                account.adjustSellReservation(symbol, deltaShares);
            } finally {
                account.lock.unlock();
            }
        }
    }

    /**
     * Atomically transfers cash from buyer to seller and shares from seller to buyer.
     * Uses canonical lock ordering (alphabetical by trader id) to avoid deadlocks.
     */
    public void settleTrade(String buyer, String seller, String symbol, int quantity, BigDecimal tradePrice,
                            BigDecimal buyerLimitPrice) {
        Account buyerAcc = getOrCreateAccount(buyer);
        Account sellerAcc = getOrCreateAccount(seller);

        if (buyer.equals(seller)) {
            // Self-trade edge case
            buyerAcc.lock.lock();
            try {
                BigDecimal tradeCost = tradePrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
                BigDecimal reservedCost = buyerLimitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
                buyerAcc.applyBuyerFill(symbol, quantity, tradeCost, reservedCost);
                buyerAcc.applySellerFill(symbol, quantity, tradeCost);
            } finally {
                buyerAcc.lock.unlock();
            }
            return;
        }

        // Canonical lock ordering: lock lower trader name first
        Account first = buyer.compareTo(seller) < 0 ? buyerAcc : sellerAcc;
        Account second = buyer.compareTo(seller) < 0 ? sellerAcc : buyerAcc;

        first.lock.lock();
        try {
            second.lock.lock();
            try {
                BigDecimal tradeCost = tradePrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
                BigDecimal reservedCost = buyerLimitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
                buyerAcc.applyBuyerFill(symbol, quantity, tradeCost, reservedCost);
                sellerAcc.applySellerFill(symbol, quantity, tradeCost);
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    public AccountSnapshot snapshot(String trader) {
        Account acc = accounts.get(trader);
        return acc != null ? acc.snapshot() : null;
    }

    public Map<String, AccountSnapshot> allSnapshots() {
        Map<String, AccountSnapshot> result = new HashMap<>();
        accounts.forEach((name, acc) -> result.put(name, acc.snapshot()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Audit report for system invariants:
     * 1. No negative balances
     * 2. Total cash conservation
     * 3. Total share conservation per symbol
     * 4. Matching open order reservations (if openOrders is non-null)
     */
    public record InvariantAuditResult(
            boolean passed,
            BigDecimal totalCash,
            BigDecimal totalReservedCash,
            Map<String, Integer> totalShares,
            Map<String, Integer> totalReservedShares,
            List<String> violations
    ) {}

    public InvariantAuditResult auditBalances() {
        return auditInvariants(null);
    }

    public InvariantAuditResult auditInvariants(List<Order> openOrders) {
        List<String> violations = new ArrayList<>();
        BigDecimal totalCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalReservedCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        Map<String, Integer> totalShares = new HashMap<>();
        Map<String, Integer> totalReservedShares = new HashMap<>();

        // Lock all accounts in canonical order to take a synchronized system-wide balance audit
        List<Account> sortedAccounts = new ArrayList<>(accounts.values());
        sortedAccounts.sort((a, b) -> a.trader.compareTo(b.trader));
        for (Account a : sortedAccounts) {
            a.lock.lock();
        }
        try {
            Set<String> allSymbols = new HashSet<>(knownSymbols);
            for (Account a : sortedAccounts) {
                AccountSnapshot snap = a.snapshot();
                allSymbols.addAll(snap.holdings().keySet());
                allSymbols.addAll(snap.reservedHoldings().keySet());
            }

            for (Account a : sortedAccounts) {
                AccountSnapshot snap = a.snapshot();
                if (snap.cash().signum() < 0) {
                    violations.add("Negative available cash for " + a.trader + ": " + snap.cash());
                }
                if (snap.reservedCash().signum() < 0) {
                    violations.add("Negative reserved cash for " + a.trader + ": " + snap.reservedCash());
                }
                totalCash = totalCash.add(a.cash());
                totalReservedCash = totalReservedCash.add(snap.reservedCash());

                for (String sym : allSymbols) {
                    int traderTotal = snap.totalShares(sym);
                    if (traderTotal < 0) {
                        violations.add("Negative shares for " + a.trader + " in " + sym + ": " + traderTotal);
                    }
                    totalShares.merge(sym, traderTotal, Integer::sum);
                    totalReservedShares.merge(sym, snap.reservedHoldings().getOrDefault(sym, 0), Integer::sum);
                }
            }
        } finally {
            for (int i = sortedAccounts.size() - 1; i >= 0; i--) {
                sortedAccounts.get(i).lock.unlock();
            }
        }

        // Validate reservations against open orders if provided
        if (openOrders != null) {
            BigDecimal expectedReservedCash = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
            Map<String, Integer> expectedReservedShares = new HashMap<>();
            for (Order o : openOrders) {
                if (o.side() == Side.BUY) {
                    expectedReservedCash = expectedReservedCash.add(
                            o.price().multiply(BigDecimal.valueOf(o.remaining()))
                    );
                } else {
                    expectedReservedShares.merge(o.symbol(), o.remaining(), Integer::sum);
                }
            }

            if (totalReservedCash.compareTo(expectedReservedCash) != 0) {
                violations.add("Reserved cash mismatch: accounts have " + totalReservedCash
                        + " but open buy orders require " + expectedReservedCash);
            }
            for (String sym : knownSymbols) {
                int actual = totalReservedShares.getOrDefault(sym, 0);
                int expected = expectedReservedShares.getOrDefault(sym, 0);
                if (actual != expected) {
                    violations.add("Reserved shares mismatch for " + sym + ": accounts have "
                            + actual + " but open sell orders require " + expected);
                }
            }
        }

        return new InvariantAuditResult(
                violations.isEmpty(),
                totalCash,
                totalReservedCash,
                totalShares,
                totalReservedShares,
                violations
        );
    }
}
