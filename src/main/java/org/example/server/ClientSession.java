package org.example.server;

import org.example.exchange.ExchangeException;
import org.example.exchange.ExchangeListener;
import org.example.exchange.Order;
import org.example.exchange.Side;
import org.example.exchange.StockExchange;
import org.example.exchange.Trade;
import org.example.protocol.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One connected client: a reader handling requests and a writer draining the outgoing message queue. */
final class ClientSession implements Runnable, ExchangeListener, Closeable {
    private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());
    private static final int MAX_LINE_LENGTH = 256;
    private static final int OUTBOX_CAPACITY = 10_000;
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String POISON = new String("<close>");

    private final Socket socket;
    private final StockExchange exchange;
    private final Consumer<ClientSession> onClose;
    private final String remote;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String trader;

    ClientSession(Socket socket, StockExchange exchange, Consumer<ClientSession> onClose) {
        this.socket = socket;
        this.exchange = exchange;
        this.onClose = onClose;
        this.remote = socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void run() {
        LOG.info(() -> "Client connected from " + remote);
        Thread.ofVirtual().name("writer-" + remote).start(this::writeLoop);
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                long start = System.nanoTime();
                String response = handle(line);
                long micros = (System.nanoTime() - start) / 1_000;
                LOG.info(String.format("[%s@%s] %s -> %s (%d us)", who(), remote, sanitize(line), response, micros));
                send(response);
                if (line.trim().equalsIgnoreCase("QUIT")) {
                    break;
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.log(Level.FINE, "Connection error with " + remote, e);
            }
        } finally {
            close();
        }
    }

    String handle(String line) {
        if (line.length() > MAX_LINE_LENGTH) {
            return "ERR Request too long";
        }
        String[] p = line.trim().split("\\s+");
        String command = p[0].toUpperCase(Locale.ROOT);
        try {
            if (trader == null && !command.equals("LOGIN") && !command.equals("QUIT")) {
                return "ERR Please LOGIN first";
            }
            return switch (command) {
                case "LOGIN" -> login(p);
                case "BUY", "SELL" -> {
                    expectArgs(p, 4);
                    Order o = exchange.placeOrder(trader, p[1].toUpperCase(Locale.ROOT), Side.valueOf(command),
                            Integer.parseInt(p[2]), new BigDecimal(p[3]));
                    yield "OK ORDER " + o.id();
                }
                case "MODIFY" -> {
                    expectArgs(p, 4);
                    Order o = exchange.modifyOrder(trader, Long.parseLong(p[1]), Integer.parseInt(p[2]),
                            new BigDecimal(p[3]));
                    yield "OK MODIFIED " + o.id();
                }
                case "CANCEL" -> {
                    expectArgs(p, 2);
                    Order o = exchange.cancelOrder(trader, Long.parseLong(p[1]));
                    yield "OK CANCELLED " + o.id();
                }
                case "ORDERS" -> {
                    exchange.openOrders().forEach(o -> send(Protocol.order(o)));
                    yield "OK ORDERS";
                }
                case "TRADES" -> {
                    exchange.trades().forEach(t -> send(Protocol.trade(t)));
                    yield "OK TRADES";
                }
                case "SYMBOLS" -> {
                    exchange.companies().forEach((s, n) -> send(Protocol.symbol(s, n)));
                    yield "OK SYMBOLS";
                }
                case "QUIT" -> "OK BYE";
                default -> "ERR Unknown command " + sanitize(command);
            };
        } catch (ExchangeException | IllegalArgumentException e) {
            return "ERR " + sanitize(e.getMessage());
        }
    }

    private String login(String[] p) {
        expectArgs(p, 2);
        if (trader != null) {
            return "ERR Already logged in as " + trader;
        }
        StockExchange.validateTrader(p[1]);
        trader = p[1];
        exchange.subscribe(this);
        return "OK WELCOME " + trader;
    }

    private static void expectArgs(String[] p, int count) {
        if (p.length != count) {
            throw new IllegalArgumentException("Expected " + (count - 1) + " argument(s) for " + p[0]);
        }
    }

    @Override
    public void onSnapshot(Map<String, String> companies, List<Order> openOrders, List<Trade> trades) {
        companies.forEach((s, n) -> send(Protocol.symbol(s, n)));
        openOrders.forEach(o -> send(Protocol.order(o)));
        trades.forEach(t -> send(Protocol.trade(t)));
    }

    @Override
    public void onOrder(Order order) {
        send(Protocol.order(order));
    }

    @Override
    public void onTrade(Trade trade) {
        send(Protocol.trade(trade));
    }

    private void send(String message) {
        if (closed.get()) {
            return;
        }
        if (!outbox.offer(message)) {
            LOG.warning(() -> "Client " + who() + "@" + remote + " is too slow, disconnecting");
            close();
        }
    }

    private void writeLoop() {
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String message = outbox.take();
                if (message == POISON) {
                    break;
                }
                out.write(message);
                out.write('\n');
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.FINE, "Writer stopped for " + remote, e);
        } finally {
            closeSocket();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exchange.unsubscribe(this);
        if (!outbox.offer(POISON)) {
            closeSocket();
        }
        onClose.accept(this);
        LOG.info(() -> "Client " + who() + "@" + remote + " disconnected");
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private String who() {
        return trader == null ? "anonymous" : trader;
    }

    private static String sanitize(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", "?");
    }
}
