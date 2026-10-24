# simulation package: what changed and why

This package implements a multi-threaded market simulator where concurrent clients arriving dynamically are simulated using platform threads, meeting the assignment requirement:
*"Clients that arrive concurrently are simulated using threads."*

## Classes Added

- **`MarketSimulator`**:
  - Spawns $N$ concurrent client threads (`clientCount`, e.g. 8 or 16).
  - Synchronizes thread release at the exact same millisecond using a `CyclicBarrier`.
  - Simulates dynamic client arrivals performing randomized trading actions:
    - 35% Limit BUY orders
    - 35% Limit SELL orders
    - 15% Direct aggressive takes (`TAKE` / `buyFromOffer`)
    - 10% Order modifications (`modifyOrder`)
    - 5% Order cancellations (`cancelOrder`)
  - Runs a concurrent background **Invariant Auditor** thread auditing financial data integrity and conservation laws in real time while threads trade.
  - Can be executed directly via CLI:
    ```powershell
    java -cp target/stock-exchange-1.0-SNAPSHOT.jar org.example.Main simulate [clients] [seconds] [safe|unsafe] [delayMs]
    ```
- **`SimulationReport`**:
  - Aggregates throughput metrics (ops/sec, trades/sec, traded dollar volume).
  - Formats and displays an integrity audit report confirming whether total cash and shares were 100% conserved and whether race conditions or violations were detected.
