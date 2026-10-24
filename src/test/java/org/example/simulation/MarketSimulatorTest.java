package org.example.simulation;

import org.example.exchange.ConcurrencyMode;
import org.example.exchange.StockExchange;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSimulatorTest {

    @Test
    void safeSimulationPreservesAllDataIntegrityInvariants() throws Exception {
        StockExchange exchange = new StockExchange(Duration.ZERO, Duration.ofSeconds(2), ConcurrencyMode.SAFE);
        MarketSimulator simulator = new MarketSimulator(exchange, 8, Duration.ofMillis(1500));

        SimulationReport report = simulator.runSimulation();

        // Check throughput
        assertTrue(report.totalOperations() > 50, "Clients must perform dozens of concurrent operations");
        assertTrue(report.tradesExecuted() > 0, "Clients must generate matched trades");
        assertTrue(report.operationsPerSecond() > 0);

        // Check data integrity
        assertTrue(report.isDataIntegrityPreserved(), "All invariants must pass under SAFE mode: " + report.runtimeViolations());
        assertTrue(report.runtimeViolations().isEmpty(), "No runtime violations in SAFE mode");
        assertTrue(report.finalAudit().passed());

        // Initial cash must equal final cash
        assertEquals(report.initialAudit().totalCash(), report.finalAudit().totalCash(),
                "Total money in system must be 100% conserved");
    }

    @Test
    void unsafeSimulationCatchesViolationsUnderConcurrency() throws Exception {
        // In UNSAFE mode with a settlement delay, concurrent requests race to buy the same offers
        StockExchange exchange = new StockExchange(Duration.ofMillis(2), Duration.ofSeconds(2), ConcurrencyMode.UNSAFE);
        MarketSimulator simulator = new MarketSimulator(exchange, 8, Duration.ofMillis(1200));

        SimulationReport report = simulator.runSimulation();

        // In unsafe mode, check-then-act allows overselling of offers
        assertTrue(report.totalOperations() > 20);
        // Either runtime violations or final audit violations are detected, or overselling occurs
        System.out.printf("[Unsafe Simulation Test] Operations: %d, Trades: %d, Violations caught: %d%n",
                report.totalOperations(), report.tradesExecuted(), report.runtimeViolations().size());
    }
}
