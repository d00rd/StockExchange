package org.example;

import org.example.client.ClientUI;
import org.example.server.ExchangeServer;
import org.example.simulation.MarketSimulator;

import java.util.Arrays;

/**
 * Usage:
 * <pre>
 *   java -jar stock-exchange.jar server [port] [safe|unsafe] [settlementDelayMs]
 *   java -jar stock-exchange.jar client [host] [port] [name]
 *   java -jar stock-exchange.jar simulate [clients] [durationSec] [safe|unsafe] [delayMs]
 * </pre>
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "client";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        switch (mode) {
            case "server" -> ExchangeServer.main(rest);
            case "client" -> ClientUI.main(rest);
            case "simulate", "sim" -> MarketSimulator.main(rest);
            default -> System.err.println("Usage: (server [port] [safe|unsafe] [delayMs]) | "
                    + "(client [host] [port] [name]) | "
                    + "(simulate [clients] [durationSec] [safe|unsafe] [delayMs])");
        }
    }
}
