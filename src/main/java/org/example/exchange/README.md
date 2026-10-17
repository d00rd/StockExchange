# exchange package: what changed and why

- **`ConcurrencyMode` (new)**: `SAFE` / `UNSAFE`. Passed to the new constructor `StockExchange(settlementDelay, modifyTimeout, mode)`. The old constructors default to `SAFE`, so nothing else changes.
- **`StockExchange.buyFromOffer` (new)**: buy directly from one sell offer, paying at most the price the client saw. This is the operation behind the three assignment cases (price lock, same shares, X + Y > available).
  - `buySafely`: check and buy under `book.lock` (correct).
  - `buyUnsafely`: check without the lock, lock only for the update (the incorrect, check-then-act version).
  - `sharesAvailable` is the shared "check" step; `executePurchase` is the shared "act" step.
- **`MutableOrder.fill`**: an order is `FILLED` when `remaining <= 0` (was `== 0`). This only matters in `UNSAFE` mode, where an offer can be oversold below zero.
- **`validatePrice`**: prices like `10.500` used to throw `ArithmeticException: Rounding necessary`. They are now normalized to `10.50`.

Full explanation and test results: [`CONCURRENCY.md`](../../../../../../CONCURRENCY.md).
