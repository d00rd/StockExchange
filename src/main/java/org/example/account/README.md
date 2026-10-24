# account package: what changed and why

This package introduces synchronized trader accounts, cash balance reservations, share holdings, and bilateral trade settlements to ensure end-to-end financial data integrity.

## Classes Added

- **`Account`**:
  - Encapsulates cash balances and share holdings for one trader.
  - Separates total ledger cash from reserved cash:
    - $\text{availableCash} = \text{cash} - \text{reservedCash}$
    - $\text{availableShares} = \text{holdings} - \text{reservedHoldings}$
  - Protected by a dedicated fair `ReentrantLock`.
  - Implements safe balance reservations (`reserveCashSafely`, `reserveSharesSafely`) and an unsafe version (`reserveCashUnsafely`) to demonstrate check-then-act double-spending / overdraft bugs under concurrency.
- **`AccountSnapshot`**: Immutable record representing a point-in-time snapshot of an account.
- **`AccountManager`**:
  - Manages all trader accounts via `ConcurrentHashMap<String, Account>`.
  - Enforces **Canonical Lock Ordering** in `settleTrade`:
    When executing a trade between a buyer and a seller, both account locks must be held. To prevent circular-wait deadlocks between concurrent cross-trades, accounts are always locked in alphabetical order of trader identifier (`traderA.compareTo(traderB) < 0`).
  - Auto-provisions new traders with starting capital by default so existing unit tests and client workflows work seamlessly.
  - Provides system-wide data integrity auditing:
    - `auditBalances()`: verifies cash conservation, share conservation per symbol, and absence of negative balances in real time.
    - `auditInvariants(openOrders)`: performs a full audit verifying that account reservations match open orders across all books.
