package org.example.server;

import org.example.exchange.StockExchange;
import org.example.protocol.Protocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** TCP server exposing a {@link StockExchange}; every client connection is served by its own virtual thread. */
public class ExchangeServer implements Closeable {
    private static final Logger LOG = Logger.getLogger(ExchangeServer.class.getName());

    private final StockExchange exchange;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ExchangeServer(StockExchange exchange, int port) throws IOException {
        this.exchange = exchange;
        this.serverSocket = new ServerSocket(port);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        Thread.ofPlatform().name("acceptor").start(this::acceptLoop);
        LOG.info(() -> "Stock exchange listening on port " + port() + ", companies: " + exchange.companies().keySet());
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                ClientSession session = new ClientSession(socket, exchange, sessions::remove);
                sessions.add(session);
                executor.submit(session);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "Accept failed", e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
        List.copyOf(sessions).forEach(ClientSession::close);
        executor.shutdown();
        LOG.info("Server stopped");
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        ServerLogging.configure(Path.of("logs"));
        StockExchange exchange = new StockExchange();
        exchange.listCompany("AAPL", "Apple Inc.");
        exchange.listCompany("MSFT", "Microsoft Corp.");
        exchange.listCompany("GOOG", "Alphabet Inc.");
        exchange.listCompany("AMZN", "Amazon.com Inc.");
        exchange.listCompany("TSLA", "Tesla Inc.");
        ExchangeServer server = new ExchangeServer(exchange, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));
        server.start();
    }
}
