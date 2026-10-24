package org.example.client;

import org.example.exchange.ExchangeException;
import org.example.exchange.Order;
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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Network client for the exchange. Requests return futures completed by the server's OK/ERR reply; snapshots and
 * live events are delivered to the {@link Listener} on the reader thread.
 */
public class ExchangeClient implements Closeable {

    public interface Listener {
        default void onSymbol(String symbol, String companyName) {
        }

        default void onOrder(Order order) {
        }

        default void onTrade(Trade trade) {
        }

        default void onDisconnected() {
        }
    }

    private final Socket socket;
    private final BufferedWriter out;
    private final Listener listener;
    private final Queue<CompletableFuture<String>> pending = new ConcurrentLinkedQueue<>();
    private final ReentrantLock sendLock = new ReentrantLock();

    public ExchangeClient(String host, int port, Listener listener) throws IOException {
        this.listener = listener;
        this.socket = new Socket(host, port);
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread.ofPlatform().daemon().name("exchange-client-reader").start(() -> readLoop(in));
    }

    public CompletableFuture<String> login(String trader) {
        return send("LOGIN " + trader);
    }

    public CompletableFuture<Long> buy(String symbol, int quantity, BigDecimal price) {
        return send("BUY " + symbol + " " + quantity + " " + price.toPlainString()).thenApply(ExchangeClient::lastLong);
    }

    public CompletableFuture<Long> sell(String symbol, int quantity, BigDecimal price) {
        return send("SELL " + symbol + " " + quantity + " " + price.toPlainString()).thenApply(ExchangeClient::lastLong);
    }

    public CompletableFuture<String> modify(long orderId, int quantity, BigDecimal price) {
        return send("MODIFY " + orderId + " " + quantity + " " + price.toPlainString());
    }

    /** Buys up to {@code quantity} shares from one sell offer, paying at most {@code maxPrice}. */
    public CompletableFuture<String> take(long offerId, int quantity, BigDecimal maxPrice) {
        return send("TAKE " + offerId + " " + quantity + " " + maxPrice.toPlainString());
    }

    public CompletableFuture<String> cancel(long orderId) {
        return send("CANCEL " + orderId);
    }

    /** Retrieves the trader's account balances and portfolio holdings. */
    public CompletableFuture<String> portfolio() {
        return send("PORTFOLIO");
    }

    /** Deposits cash into the trader's exchange account. */
    public CompletableFuture<String> deposit(BigDecimal amount) {
        return send("DEPOSIT " + amount.toPlainString());
    }

    /** Sends a raw protocol command; the future completes with the text after "OK" or fails with the ERR text. */
    public CompletableFuture<String> send(String command) {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Command must be a single line");
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        sendLock.lock();
        try {
            // Enqueue and write under one lock so replies (which arrive in request order) match their futures.
            pending.add(future);
            out.write(command);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            pending.remove(future);
            future.completeExceptionally(e);
        } finally {
            sendLock.unlock();
        }
        return future;
    }

    private void readLoop(BufferedReader in) {
        try (in) {
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException ignored) {
            // connection closed
        } finally {
            CompletableFuture<String> f;
            while ((f = pending.poll()) != null) {
                f.completeExceptionally(new IOException("Disconnected from server"));
            }
            listener.onDisconnected();
        }
    }

    private void dispatch(String line) {
        int space = line.indexOf(' ');
        String type = space < 0 ? line : line.substring(0, space);
        String rest = space < 0 ? "" : line.substring(space + 1);
        switch (type) {
            case "OK" -> complete(f -> f.complete(rest));
            case "ERR" -> complete(f -> f.completeExceptionally(new ExchangeException(rest)));
            case "ORDER" -> listener.onOrder(Protocol.parseOrder(line));
            case "TRADE" -> listener.onTrade(Protocol.parseTrade(line));
            case "SYMBOL" -> {
                int nameStart = rest.indexOf(' ');
                listener.onSymbol(nameStart < 0 ? rest : rest.substring(0, nameStart),
                        nameStart < 0 ? "" : rest.substring(nameStart + 1));
            }
            default -> {
                // ignore unknown messages for forward compatibility
            }
        }
    }

    private void complete(Consumer<CompletableFuture<String>> action) {
        CompletableFuture<String> f = pending.poll();
        if (f != null) {
            action.accept(f);
        }
    }

    private static long lastLong(String reply) {
        return Long.parseLong(reply.substring(reply.lastIndexOf(' ') + 1));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
