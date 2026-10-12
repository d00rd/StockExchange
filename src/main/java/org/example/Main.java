package org.example;

import org.example.client.ClientUI;
import org.example.server.ExchangeServer;

import java.util.Arrays;

/**
 * Usage:
 * <pre>
 *   java -jar stock-exchange.jar server [port]
 *   java -jar stock-exchange.jar client [host] [port] [name]
 * </pre>
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "client";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        switch (mode) {
            case "server" -> ExchangeServer.main(rest);
            case "client" -> ClientUI.main(rest);
            default -> System.err.println("Usage: (server [port]) | (client [host] [port] [name])");
        }
    }
}
