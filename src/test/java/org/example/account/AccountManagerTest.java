package org.example.account;

import org.example.exchange.ConcurrencyMode;
import org.example.exchange.ExchangeException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountManagerTest {

    @Test
    void depositAndHoldings() {
        AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
        am.createAccount("alice", new BigDecimal("1000.00"), Map.of("AAPL", 50));

        AccountSnapshot snap = am.snapshot("alice");
        assertNotNull(snap);
        assertEquals(new BigDecimal("1000.00"), snap.cash());
        assertEquals(BigDecimal.ZERO.setScale(2), snap.reservedCash());
        assertEquals(50, snap.availableShares("AAPL"));

        am.deposit("alice", new BigDecimal("250.50"));
        assertEquals(new BigDecimal("1250.50"), am.snapshot("alice").cash());
    }

    @Test
    void reservationAndInsufficientFunds() {
        AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
        am.createAccount("bob", new BigDecimal("100.00"), Map.of("MSFT", 10));

        // Bob has $100, reserves $80 (8 * $10.00)
        am.reserveForBuy("bob", 8, new BigDecimal("10.00"), ConcurrencyMode.SAFE, 0);
        AccountSnapshot snap = am.snapshot("bob");
        assertEquals(new BigDecimal("20.00"), snap.cash()); // available
        assertEquals(new BigDecimal("80.00"), snap.reservedCash());

        // Bob tries to reserve another $30 -> throws Insufficient funds
        assertThrows(ExchangeException.class, () ->
                am.reserveForBuy("bob", 3, new BigDecimal("10.00"), ConcurrencyMode.SAFE, 0));

        // Shares reservation
        am.reserveForSell("bob", "MSFT", 7);
        assertEquals(3, am.snapshot("bob").availableShares("MSFT"));
        assertEquals(7, am.snapshot("bob").reservedHoldings().get("MSFT"));

        // Over-selling shares -> throws Insufficient shares
        assertThrows(ExchangeException.class, () ->
                am.reserveForSell("bob", "MSFT", 5));
    }

    @Test
    void bilateralSettlementConservesMoneyAndShares() {
        AccountManager am = new AccountManager(false, BigDecimal.ZERO, 0);
        am.createAccount("alice", new BigDecimal("500.00"), Map.of("AAPL", 0));
        am.createAccount("bob", new BigDecimal("200.00"), Map.of("AAPL", 20));

        // Alice reserves for buy of 10 shares @ 15.00 limit = $150.00
        am.reserveForBuy("alice", 10, new BigDecimal("15.00"), ConcurrencyMode.SAFE, 0);
        // Bob reserves for sell of 10 shares
        am.reserveForSell("bob", "AAPL", 10);

        // Trade settles at 12.00 (price improvement for Alice!)
        am.settleTrade("alice", "bob", "AAPL", 10, new BigDecimal("12.00"), new BigDecimal("15.00"));

        AccountSnapshot aliceSnap = am.snapshot("alice");
        AccountSnapshot bobSnap = am.snapshot("bob");

        // Alice paid 10 * 12.00 = 120.00. Initial was 500.00 -> remaining is 380.00. Reserved is 0.
        assertEquals(new BigDecimal("380.00"), aliceSnap.cash());
        assertEquals(new BigDecimal("0.00"), aliceSnap.reservedCash());
        assertEquals(10, aliceSnap.availableShares("AAPL"));

        // Bob received 120.00. Initial was 200.00 -> new is 320.00.
        assertEquals(new BigDecimal("320.00"), bobSnap.cash());
        assertEquals(10, bobSnap.availableShares("AAPL"));

        // Invariant: Total cash = 500 + 200 = 700. Now: 380 + 320 = 700!
        assertEquals(new BigDecimal("700.00"), aliceSnap.cash().add(bobSnap.cash()));
        // Total shares = 0 + 20 = 20. Now: 10 + 10 = 20!
        assertEquals(20, aliceSnap.availableShares("AAPL") + bobSnap.availableShares("AAPL"));
    }
}
