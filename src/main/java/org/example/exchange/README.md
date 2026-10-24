# exchange package: what changed and why

- **`AccountManager` Integration**:
  - `StockExchange` connects directly with `AccountManager` for balance reservations and bilateral settlement.
  - When placing orders (`placeOrder`), funds (for BUY) or shares (for SELL) are reserved before order entry.
  - When orders are modified (`modifyOrder`) or cancelled (`cancelOrder`), reservations are automatically adjusted or released.
  - When orders match (`match`) or a client buys an offer (`buyFromOffer`), `settleTrade` transfers cash from buyer to seller and shares from seller to buyer under canonical lock ordering. If the match executes below the buyer's limit price, price improvement savings are automatically refunded.
- **`Order.remaining()`**: Added alias for `quantity()` to clarify order snapshots in audit checks.
- **`ConcurrencyMode`**: `SAFE` / `UNSAFE`. Passed to the constructor `StockExchange(settlementDelay, modifyTimeout, mode, accountManager)`.
- **`StockExchange.buyFromOffer`**: buy directly from one sell offer, paying at most the price the client saw.
  - `buySafely`: check and buy under `book.lock` (correct).
  - `buyUnsafely`: check without the lock, lock only for the update (the incorrect, check-then-act version).
- **`MutableOrder.fill`**: an order is `FILLED` when `remaining <= 0` (was `== 0`). This matters in `UNSAFE` mode where an offer can be oversold below zero.
- **`validatePrice`**: prices like `10.500` are normalized to `10.50`.

Full explanation and test results: [`CONCURRENCY.md`](../../../../../../CONCURRENCY.md).
