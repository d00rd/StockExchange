package org.example.simulation;

import org.example.account.AccountManager.InvariantAuditResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Summary metrics and data integrity results of a concurrent market simulation.
 */
public record SimulationReport(
        int clientCount,
        long durationMs,
        long totalOperations,
        long buyOrdersPlaced,
        long sellOrdersPlaced,
        long takesAttempted,
        long modificationsAttempted,
        long cancelsAttempted,
        long tradesExecuted,
        BigDecimal totalVolumeTraded,
        double operationsPerSecond,
        double tradesPerSecond,
        InvariantAuditResult initialAudit,
        InvariantAuditResult finalAudit,
        List<String> runtimeViolations
) {
    public boolean isDataIntegrityPreserved() {
        return runtimeViolations.isEmpty() && finalAudit != null && finalAudit.passed();
    }

    public void printSummary() {
        System.out.println("================================================================================");
        System.out.println("                   CONCURRENT MARKET SIMULATION REPORT                          ");
        System.out.println("================================================================================");
        System.out.printf("  Concurrent Client Threads : %d%n", clientCount);
        System.out.printf("  Simulation Duration       : %d ms (%.2f s)%n", durationMs, durationMs / 1000.0);
        System.out.printf("  Total Client Operations   : %d (%.1f ops/sec)%n", totalOperations, operationsPerSecond);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  OPERATIONS BREAKDOWN:");
        System.out.printf("    - Limit Buys Placed     : %d%n", buyOrdersPlaced);
        System.out.printf("    - Limit Sells Placed    : %d%n", sellOrdersPlaced);
        System.out.printf("    - Direct Takes (TAKE)   : %d%n", takesAttempted);
        System.out.printf("    - Order Modifications   : %d%n", modificationsAttempted);
        System.out.printf("    - Order Cancellations   : %d%n", cancelsAttempted);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  MATCHING ENGINE EXECUTION:");
        System.out.printf("    - Total Trades Settled  : %d (%.1f trades/sec)%n", tradesExecuted, tradesPerSecond);
        System.out.printf("    - Total Traded Volume   : $%s%n", totalVolumeTraded.toPlainString());
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  DATA INTEGRITY & SYNCHRONIZATION AUDIT:");
        boolean ok = isDataIntegrityPreserved();
        System.out.printf("    - Overall Integrity     : %s%n", ok ? "PASSED (100% Invariants Preserved)" : "VIOLATIONS DETECTED");
        System.out.printf("    - Runtime Violations    : %d%n", runtimeViolations.size());
        if (finalAudit != null) {
            System.out.printf("    - System Total Cash     : $%s (Conserved: %s)%n",
                    finalAudit.totalCash(),
                    initialAudit != null && initialAudit.totalCash().compareTo(finalAudit.totalCash()) == 0 ? "YES" : "NO");
            System.out.println("    - Total Shares Conserved: ");
            for (Map.Entry<String, Integer> e : finalAudit.totalShares().entrySet()) {
                int initShares = initialAudit != null ? initialAudit.totalShares().getOrDefault(e.getKey(), 0) : 0;
                System.out.printf("        * %-6s : %d shares (Initial: %d, Match: %s)%n",
                        e.getKey(), e.getValue(), initShares, initShares == e.getValue() ? "YES" : "NO");
            }
        }
        if (!ok) {
            System.out.println("  VIOLATION DETAILS:");
            runtimeViolations.forEach(v -> System.out.println("    [!] " + v));
            if (finalAudit != null) {
                finalAudit.violations().forEach(v -> System.out.println("    [!] " + v));
            }
        }
        System.out.println("================================================================================");
    }
}
