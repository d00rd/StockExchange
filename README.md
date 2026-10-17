# Stock Exchange

A Java 23 stock exchange implemented as a TCP server with Swing clients. Buyers submit requests, sellers submit offers, and the server matches compatible orders while broadcasting the public order book and trade history to connected clients.

## TODO (To be implemented)

### Main task
Make sure at least 3 concurrency cases are implemented in 2 ways(correct and incorrect), use relevant concurrency primitives and describe what problems they resolve(race conditions, deadlock...). Test both versions and maybe gather information on how one version is better.

**Done:** see [CONCURRENCY.md](CONCURRENCY.md). It covers the price lock, two buyers on the same shares, and X + Y > available, each with a `SAFE` and an `UNSAFE` version, tests and measured results.

Would be nice to add an README.md file in each folder where you make changes explaining what changed and why, helps the other teammates and the AI ;) 

### Optional
- Right now we cannot run multiple clients on the same machine, could be something to look into. (They do work from separate terminals; in IntelliJ, enable *Modify options -> Allow multiple instances* on the run configuration.)
- Improving the login, implement an database
- Improving the UI

## Project Structure

- `src/main/java/org/example/Main.java` selects server or client mode.
- `src/main/java/org/example/exchange/` contains the exchange model and matching engine. `StockExchange` owns company order books, order state, trade history, and subscriptions. `OrderBook` keeps bids and asks in price-time priority. `Order` and `Trade` are immutable snapshots; `MutableOrder` is internal state.
- `src/main/java/org/example/protocol/Protocol.java` encodes and decodes the line-oriented TCP messages.
- `src/main/java/org/example/server/` accepts TCP connections, processes client requests, publishes updates, and logs request/response pairs. `ExchangeServer` uses a virtual-thread-per-client executor; `ClientSession` separates socket reading from queued socket writing.
- `src/main/java/org/example/client/ExchangeClient.java` is the asynchronous TCP client API. `ClientUI.java` is the Swing trading interface.
- `src/test/java/org/example/exchange/` tests the matching rules and engine-level concurrency.
- `src/test/java/org/example/server/ExchangeServerIntegrationTest.java` tests actual clients communicating with an actual local TCP server.

## Matching Rules

A buy request can match a sell offer when the bid price is at least the ask price. The traded quantity is the smaller remaining quantity. The higher-priority order is selected first by price and then by arrival sequence: bids prefer higher prices, asks prefer lower prices, and equal-price orders are FIFO. A trade executes at the limit price of whichever order was already resting in the book. Any unfilled remainder stays open.

Prices use `BigDecimal`, are positive, and have at most two fractional digits. Quantities must be between 1 and 1,000,000 shares. Symbols must be uppercase letters/digits beginning with a letter. Trader IDs are 1-20 letters, digits, or underscores.

## Concurrency and Operation Safety

### Exchange order books

Each company has its own fair `ReentrantLock` in `OrderBook`. Operations that can read or mutate that company's order state acquire this lock. The lock is the serialization point for a symbol: separate requests for the same company behave as if applied one at a time, while unrelated companies can trade concurrently. It protects both bid/ask collections and the mutable order fields associated with that book.

- **List a company:** `ConcurrentHashMap.putIfAbsent` safely creates a book and prevents concurrent listing calls from replacing the first book for a symbol.
- **Place a buy or sell:** input is validated before acquiring the company's lock. Under the lock, the engine assigns an arrival sequence, records the order, inserts it by price-time priority, and runs matching. All these steps happen as one serialized operation relative to other activity on that company's book. The same lock remains held while each trade is settled and recorded, preventing two buyers or sellers from consuming the same remaining shares.
- **Match and settle:** while both sides exist and the best bid crosses the best ask, the engine transfers `min(bid remaining, ask remaining)`, updates both orders, records the trade, removes completed orders, then publishes events. These state changes occur under the company lock. The optional `settlementDelay` simulates a transaction in progress while keeping the book locked.
- **Modify:** the trader must own the order, its new price and quantity are validated, and the company lock is acquired with `tryLock` up to `modifyTimeout` (2 seconds by default). If unavailable, the operation fails with a retryable exchange error. Once locked, the order must still be open. It is removed, changed, reinserted, and matching runs before releasing the lock. A price change or quantity increase loses price-time priority; a reduction at the same price retains the order's sequence.
- **Buy from a specific offer (`TAKE`):** the client sends the offer id, the quantity and the price it saw. Under the company lock, the engine checks that the offer is still open and not more expensive than that price, gives the buyer `min(quantity, remaining)`, and records the trade. A purchase racing a price increase, another buyer, or a too-small offer can therefore never overpay or oversell. An intentionally broken `UNSAFE` mode, which checks outside the lock, exists for comparison; see [CONCURRENCY.md](CONCURRENCY.md).
- **Cancel:** uses the same ownership check and timed company lock as modification. It only changes an open order and removes it from the book while locked. A cancel racing a match therefore either wins before the match, or observes a filled/non-open order after it; it cannot both cancel and trade the same shares.
- **Read an order or book:** `getOrder` and `openOrders(symbol)` acquire the relevant company lock and return immutable `Order` snapshots, so callers do not see partially updated mutable state. `openOrders()` visits companies in sorted symbol order and gathers each book separately.
- **Trade history:** trades are immutable records stored in a `Collections.synchronizedList`. The accessor synchronizes around copying the history into an immutable list.
- **Subscribe to events:** the engine locks all current company books in sorted order, captures company names, open orders, and trade history, calls the listener with the snapshot, then registers it before unlocking. This avoids a gap between a client's initial snapshot and live updates and uses a consistent lock order to avoid deadlock among simultaneous subscriptions.

The order, trade, and arrival-sequence IDs use independent `AtomicLong`s. These provide unique increasing IDs/sequences across concurrent calls without a global lock.

### Server and network clients

- The accept loop submits each connection to `Executors.newVirtualThreadPerTaskExecutor()`. A session reads requests on its task thread and starts a virtual writer thread for server output.
- Each session has a bounded `LinkedBlockingQueue` for replies, snapshots, and live events. Exchange callbacks enqueue data instead of writing to the socket while an order-book lock is held. A separate writer drains the queue and owns the output stream; if the queue fills, the slow client is disconnected instead of allowing an unbounded backlog.
- `AtomicBoolean.compareAndSet` makes session shutdown idempotent when initiated by either the reader, writer, server, or queue-overflow path. `ConcurrentHashMap.newKeySet()` tracks sessions safely across accept/close threads.
- On the client, a dedicated reader thread dispatches server events. `ConcurrentLinkedQueue` holds pending request futures. A `ReentrantLock` covers both adding a future and writing its command, so concurrent callers cannot enqueue futures in a different order from requests written to the socket. The protocol guarantees one `OK` or `ERR` response per request, in request order; unsolicited `ORDER`, `TRADE`, and `SYMBOL` messages are dispatched separately.
- The Swing UI sends all server callbacks to the Swing Event Dispatch Thread using `SwingUtilities.invokeLater`. Its mutable UI collections and table models are therefore only accessed on that thread.

Server callbacks to `ExchangeListener` are synchronous while an order-book lock is held. The built-in network listener only performs a non-blocking enqueue; other listeners should also be fast and non-blocking.

## Tests

Run the tests with Maven as described below. `mvn test` runs 31 JUnit tests in four classes:

- `ConcurrencyCasesTest` runs the three assignment cases (price lock, same shares, X + Y > available) 200 times each in `SAFE` and `UNSAFE` mode. Safe must never break the rule; unsafe must be caught breaking it. It prints the violation rate and the time taken.

- `StockExchangeTest` verifies basic matching behavior, partial fills, no match below the ask, price/time priority, price of execution, modification triggering a match, ownership and status rules, invalid inputs, and listener events.
- `StockExchangeConcurrencyTest` uses a fixed thread pool and a `CyclicBarrier`: each competing task waits at the barrier, then all are released to make their operation at nearly the same time. The two-buyer, partial-offer, two-seller, cancel-versus-buy, and modify-versus-buy races each repeat 300 times. Assertions check that trades are exclusive, quantities never exceed orders, and final order states are consistent. Two additional tests configure a settlement delay to hold the book lock long enough to verify modification rejection/timeout behavior and that another company's book is not blocked. A stress test runs 16 trader tasks, each performing 1,000 randomized place/modify/cancel operations on two companies, then reconciles every order's filled quantity against the trade history and checks that no crossed book remains.
- `ExchangeServerIntegrationTest` starts a real server on an ephemeral local port and connects real TCP clients. It races two buyer clients at a barrier for the same offer over 25 rounds, checks all clients receive live trades, verifies a late client's initial snapshot, checks order ownership and malformed request handling, and captures server logs to check that requests and responses are recorded.

The barrier synchronizes task starts; it does not force a particular winner. The tests assert invariants that must hold regardless of which contender acquires the lock first.

## Build and Run

Requires JDK 23 and Maven.

```powershell
mvn test
mvn package
```

Start the server (the default port is 5000):

```powershell
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main server 5000
```

Optional arguments `server [port] [safe|unsafe] [settlementDelayMs]` start the intentionally broken mode and/or slow transactions down, for demonstrating the concurrency cases by hand, e.g. `server 5000 unsafe 3000`.

Requires `JAVA_HOME` to point to JDK 23 (for example `C:\Program Files\Java\jdk-23`); with JDK 17 Maven fails with `invalid target release: 23`.

Start one or more clients in separate terminals:

```powershell
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main client localhost 5000 alice
```

The client opens a login dialog, then displays a shared order book, transaction history, and controls to place, modify, or cancel your own orders. The server starts with AAPL, MSFT, GOOG, AMZN, and TSLA listed. Server request logs are printed to the console and written to rotating `logs/server-*.log` files.

## Scope Notes

This is an educational exchange simulation over TCP, not a production trading venue. Trader names are not authenticated, and there are no cash balances, share ownership checks, persistence, TLS, or exchange recovery after server restart. All in-memory orders and trade history are lost when the server stops.
