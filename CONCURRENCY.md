# Concurrency cases: correct vs. incorrect

This file covers the three concurrency cases the assignment asks for. Each one has a correct and an incorrect version, plus tests that compare them.

All three cases go through one operation: **buying directly from a specific sell offer** (`TAKE`). In the UI, a client selects a row in the *Offers* table and clicks **Buy selected offer**. The client sends the price it saw as the highest price it agrees to pay.

```
StockExchange.buyFromOffer(trader, offerId, quantity, maxPrice)
  CHECK: is the offer still open? is its price <= maxPrice?  -> shares = min(quantity, remaining)
  ACT:   (settlement time) fill the buyer, fill the offer, record the trade
```

The engine runs in one of two modes (`ConcurrencyMode`, passed to the `StockExchange` constructor):

| Mode | What it does |
|---|---|
| `SAFE` (default) | CHECK and ACT run **inside the same `book.lock`** (`StockExchange.buySafely`). |
| `UNSAFE` | CHECK runs **without the lock**, then the code waits for the settlement time and only locks for the ACT (`StockExchange.buyUnsafely`). |

The incorrect version is not a made-up crash. It is the most common real concurrency bug: **check-then-act** (also called TOCTOU, "time of check to time of use"). The writes still happen under the lock, so the data structures don't get corrupted. The results are wrong instead: shares get oversold, offers go negative, and buyers pay the wrong price.

---

## Case 1: the price goes up while the client is buying

**Scenario.** A seller offers 100 shares at 10.00. Alice sees 10.00 and clicks *Buy*. While her purchase is being processed, the seller changes the offer to 12.00 (`modifyOrder`).

**Incorrect (`UNSAFE`).** Alice's thread checks the price (10.00 ≤ 10.00, OK) without the lock. During the settlement window the seller takes the lock and raises the price. Alice's thread then locks and executes at the **current** price, 12.00. She pays 20% more than she agreed to.
There is also a *data race* here: `MutableOrder.price` is read without the lock, so Java doesn't even guarantee that Alice's thread sees the latest value.

**Correct (`SAFE`).** The price check happens while holding `book.lock`, and the lock is held until the trade is recorded. The seller's `modifyOrder` must also get that lock (`lockForModification`, a timed `tryLock`). So there are only two possible orders of events:
- Alice first: she buys at 10.00, and the seller's modification then fails because the offer is already `FILLED`.
- Seller first: the price is 12.00 when Alice checks, so she is rejected with *"Price of offer N is now 12.00, above your limit 10.00"*. She never pays the higher price.

The price is "locked" for the length of the transaction.

**Primitives.** `ReentrantLock(true)` (fair) for mutual exclusion. `tryLock(timeout)` on the seller's side, so a seller can't wait forever while a purchase is in progress.

## Case 2: two clients buy the same shares at the same time

**Scenario.** One offer of 100 shares. Alice and Bob both try to buy all 100 at the same moment.

**Incorrect (`UNSAFE`).** Both threads read `remaining = 100` before either one updates it, and both buy 100. That is **200 shares sold from an offer of 100**: a classic race condition / lost update.

**Correct (`SAFE`).** The lock makes "check remaining + subtract" one atomic step. Whoever gets the lock first buys 100. The other one then sees `remaining = 0` and gets *"Offer N is no longer available"*.

**Primitive.** `ReentrantLock` per company (`OrderBook.lock`). The lock is per symbol, so buyers of *different* companies are never blocked by each other.

## Case 3: X + Y requested, but fewer than X + Y available

**Scenario.** One offer of 100 shares. Alice wants 70 and Bob wants 50 (120 in total) at the same moment.

**Incorrect (`UNSAFE`).** Both see 100 available, so Alice gets 70 and Bob gets 50. **120 shares are sold and the offer ends at -20.**

**Correct (`SAFE`).** The second buyer gets a **partial fill** of whatever is left, `min(requested, remaining)`. The only possible results are 70 + 30 (Alice first) or 50 + 50 (Bob first). Exactly 100 are always sold and the offer ends at 0. The lock is fair, so neither buyer can be starved by the other.

**Primitive.** The same `ReentrantLock`. The rule it protects is different: in case 2 it's "only one buyer wins"; here it's "the total never exceeds the supply". Partial fills are only correct if the amount is calculated inside the lock.

---

## How it's tested

`src/test/java/org/example/exchange/ConcurrencyCasesTest.java` runs each case for 200 rounds in both modes. It uses a `CyclicBarrier` to release the competing threads at the same instant and a 2 ms simulated settlement time. A round counts as a *violation* if the case's rule is broken:

| Case | Rule checked each round |
|---|---|
| 1 Price lock | no trade above 10.00 |
| 2 Same shares | exactly 100 shares sold, exactly one buyer succeeded |
| 3 X + Y > available | exactly 100 sold, offer left at 0 |

The `SAFE` tests assert 0 violations. The `UNSAFE` tests assert more than 0 (the bug was observed). Each test prints a summary line.

### Results (Windows 11, JDK 23, `mvn test`)

| Case | SAFE violations | UNSAFE violations | SAFE time | UNSAFE time |
|---|---|---|---|---|
| 1 Price lock | 0 / 200 (0%) | 181–189 / 200 (~93%) | ~730 ms | ~640 ms |
| 2 Same shares | 0 / 200 (0%) | 200 / 200 (100%) | ~750 ms | ~670 ms |
| 3 X + Y > available | 0 / 200 (0%) | 200 / 200 (100%) | ~1320 ms | ~650 ms |

**What the numbers say.**
- The unsafe version is wrong almost every time once two requests overlap. It's not a rare edge case.
- Case 1 isn't at 100% because in some rounds the seller's change lands before Alice even checks. She is then correctly rejected in both modes.
- **Correctness costs throughput:** in case 3 the safe version is about 2× slower. Both purchases succeed there, so the two settlements run one after the other (2 × 2 ms) instead of overlapping. In case 2 the difference is small, because the losing buyer is rejected right away. The lock is per company, which keeps this cost limited to buyers of the same stock.
- Exact times depend on the machine and OS timer resolution. Run `mvn test` and look for the `[case] MODE violations:` lines to get your own numbers.

## Try it by hand (two real clients)

```powershell
mvn package -DskipTests
# terminal 1: unsafe mode with a 3 s settlement time, so two humans can click "at the same time"
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main server 5000 unsafe 3000
# terminals 2, 3, 4
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main client localhost 5000 seller
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main client localhost 5000 alice
java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main client localhost 5000 bob
```
1. `seller` places a SELL of 100 AAPL @ 10.00.
2. `alice` (Quantity 70) and `bob` (Quantity 50) select the offer and click **Buy selected offer** within 3 seconds of each other.
3. With `unsafe`, both succeed and the offer shows **-20**. Restart the server with `safe` instead and the second buyer gets only 30.
4. For case 1: `alice` clicks Buy, and within 3 s `seller` modifies the offer to 12.00. With `unsafe`, Alice's log shows a purchase at 12.00. With `safe`, the seller's modify is rejected ("Transaction in progress", or the offer is already filled).

## Other concurrency already in the project (all correct)

See the "Concurrency and Operation Safety" section in `README.md`. In short: a lock per order book with sorted lock ordering in `subscribe` (prevents deadlock), a timed `tryLock` for modify/cancel, `AtomicLong` IDs, the server's virtual threads and bounded outbox queue (a slow client never blocks an order-book lock), the client's lock that pairs each request with its reply future, and the Swing EDT.
