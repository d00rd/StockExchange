package org.example.client;

import org.example.exchange.Order;
import org.example.exchange.OrderStatus;
import org.example.exchange.Side;
import org.example.exchange.StockExchange;
import org.example.exchange.Trade;
import org.example.protocol.Protocol;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Swing trading terminal. All mutable state is only touched on the Event Dispatch Thread. */
public class ClientUI extends JFrame implements ExchangeClient.Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String trader;
    private ExchangeClient client;
    private final Map<Long, Order> openOrders = new HashMap<>();

    private final DefaultComboBoxModel<String> symbols = new DefaultComboBoxModel<>();
    private final JComboBox<String> symbolBox = new JComboBox<>(symbols);
    private final JComboBox<Side> sideBox = new JComboBox<>(Side.values());
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(10, 1, StockExchange.MAX_QUANTITY, 1));
    private final JTextField price = new JTextField("100.00", 8);
    private final OrderTableModel requests = new OrderTableModel();
    private final OrderTableModel offers = new OrderTableModel();
    private final JTable requestTable = new JTable(requests);
    private final JTable offerTable = new JTable(offers);
    private final TradeTableModel tradeModel = new TradeTableModel();
    private final JTextArea log = new JTextArea(6, 80);
    private JTable selectedTable = requestTable;

    public ClientUI(String trader) {
        super("Stock Exchange - " + trader);
        this.trader = trader;
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeClient();
            }
        });

        JButton place = new JButton("Place order");
        place.addActionListener(e -> placeOrder());
        symbolBox.addActionListener(e -> refreshBook());
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT));
        form.add(new JLabel("Symbol"));
        form.add(symbolBox);
        form.add(new JLabel("Side"));
        form.add(sideBox);
        form.add(new JLabel("Quantity"));
        form.add(quantity);
        form.add(new JLabel("Price"));
        form.add(price);
        form.add(place);

        configureOrderTable(requestTable, offerTable);
        configureOrderTable(offerTable, requestTable);
        JPanel book = new JPanel(new GridLayout(1, 2, 8, 0));
        book.add(titled(new JScrollPane(requestTable), "Requests (buyers)"));
        book.add(titled(new JScrollPane(offerTable), "Offers (sellers)"));
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, book,
                titled(new JScrollPane(new JTable(tradeModel)), "Transaction history (all symbols)"));
        center.setResizeWeight(0.55);

        JButton modify = new JButton("Modify selected");
        modify.addActionListener(e -> modifySelected());
        JButton cancel = new JButton("Cancel selected");
        cancel.addActionListener(e -> cancelSelected());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(modify);
        actions.add(cancel);
        log.setEditable(false);
        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.NORTH);
        south.add(titled(new JScrollPane(log), "Activity"), BorderLayout.CENTER);

        getContentPane().add(form, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(1000, 720);
        setLocationByPlatform(true);
    }

    void connect(String host, int port) throws IOException {
        client = new ExchangeClient(host, port, this);
        track("Login as " + trader, client.login(trader));
    }

    private void configureOrderTable(JTable table, JTable other) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                selectedTable = table;
                other.clearSelection();
            }
        });
    }

    private static JScrollPane titled(JScrollPane pane, String title) {
        pane.setBorder(BorderFactory.createTitledBorder(title));
        return pane;
    }

    private void placeOrder() {
        String symbol = (String) symbolBox.getSelectedItem();
        Side side = (Side) sideBox.getSelectedItem();
        int qty = (Integer) quantity.getValue();
        BigDecimal limit = parsePrice(price.getText());
        if (symbol == null || side == null || limit == null) {
            return;
        }
        String what = side + " " + qty + " " + symbol + " @ " + limit.toPlainString();
        track(what, side == Side.BUY ? client.buy(symbol, qty, limit) : client.sell(symbol, qty, limit));
    }

    private void modifySelected() {
        Order order = selectedOwnOrder();
        if (order == null) {
            return;
        }
        JSpinner qty = new JSpinner(new SpinnerNumberModel(order.quantity(), 1, StockExchange.MAX_QUANTITY, 1));
        JTextField newPrice = new JTextField(order.price().toPlainString());
        JPanel panel = new JPanel(new GridLayout(2, 2, 4, 4));
        panel.add(new JLabel("Quantity"));
        panel.add(qty);
        panel.add(new JLabel("Price"));
        panel.add(newPrice);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Modify order " + order.id(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        BigDecimal limit = choice == JOptionPane.OK_OPTION ? parsePrice(newPrice.getText()) : null;
        if (limit != null) {
            int q = (Integer) qty.getValue();
            track("Modify #" + order.id() + " to " + q + " @ " + limit.toPlainString(),
                    client.modify(order.id(), q, limit));
        }
    }

    private void cancelSelected() {
        Order order = selectedOwnOrder();
        if (order != null) {
            track("Cancel #" + order.id(), client.cancel(order.id()));
        }
    }

    private Order selectedOwnOrder() {
        int row = selectedTable.getSelectedRow();
        if (row < 0) {
            log("Select one of your orders first");
            return null;
        }
        Order order = ((OrderTableModel) selectedTable.getModel()).get(row);
        if (!order.trader().equals(trader)) {
            log("Order #" + order.id() + " belongs to " + order.trader() + "; you can only change your own orders");
            return null;
        }
        return order;
    }

    private BigDecimal parsePrice(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            log("Invalid price: " + text);
            return null;
        }
    }

    private void track(String what, CompletableFuture<?> future) {
        future.whenComplete((reply, error) -> SwingUtilities.invokeLater(() ->
                log(error == null ? what + " -> OK " + reply : what + " -> FAILED: " + rootMessage(error))));
    }

    private static String rootMessage(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null ? t.getCause() : t).getMessage();
    }

    private void refreshBook() {
        String symbol = (String) symbolBox.getSelectedItem();
        requests.set(bookSide(symbol, Side.BUY, Comparator.comparing(Order::price).reversed()));
        offers.set(bookSide(symbol, Side.SELL, Comparator.comparing(Order::price)));
    }

    private List<Order> bookSide(String symbol, Side side, Comparator<Order> priority) {
        return openOrders.values().stream()
                .filter(o -> o.symbol().equals(symbol) && o.side() == side)
                .sorted(priority.thenComparingLong(Order::id))
                .toList();
    }

    private void log(String message) {
        log.append(TIME.format(Instant.now()) + "  " + message + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
                // exiting
            }
        }
    }

    // ---- events from the server (reader thread) ----

    @Override
    public void onSymbol(String symbol, String companyName) {
        SwingUtilities.invokeLater(() -> {
            if (symbols.getIndexOf(symbol) < 0) {
                symbols.addElement(symbol);
            }
        });
    }

    @Override
    public void onOrder(Order order) {
        SwingUtilities.invokeLater(() -> {
            if (order.status() == OrderStatus.OPEN) {
                openOrders.put(order.id(), order);
            } else {
                openOrders.remove(order.id());
                if (order.trader().equals(trader) && order.status() == OrderStatus.FILLED) {
                    log("Your order #" + order.id() + " (" + order.side() + " " + order.symbol() + ") is fully filled");
                }
            }
            refreshBook();
        });
    }

    @Override
    public void onTrade(Trade trade) {
        SwingUtilities.invokeLater(() -> {
            tradeModel.add(trade);
            if (trade.buyer().equals(trader) || trade.seller().equals(trader)) {
                log("You " + (trade.buyer().equals(trader) ? "bought " : "sold ") + trade.quantity() + " "
                        + trade.symbol() + " @ " + trade.price().toPlainString());
            }
        });
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> log("Disconnected from server"));
    }

    private final class OrderTableModel extends AbstractTableModel {
        private final String[] columns = {"Order", "Trader", "Quantity", "Filled", "Price"};
        private List<Order> rows = List.of();

        void set(List<Order> newRows) {
            rows = newRows;
            fireTableDataChanged();
        }

        Order get(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Order o = rows.get(row);
            return switch (column) {
                case 0 -> o.id();
                case 1 -> o.trader().equals(trader) ? o.trader() + " (you)" : o.trader();
                case 2 -> o.quantity();
                case 3 -> o.filled();
                default -> o.price().toPlainString();
            };
        }
    }

    private static final class TradeTableModel extends AbstractTableModel {
        private final String[] columns = {"Time", "Symbol", "Quantity", "Price", "Buyer", "Seller"};
        private final List<Trade> rows = new ArrayList<>();

        void add(Trade trade) {
            rows.addFirst(trade);
            fireTableRowsInserted(0, 0);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Trade t = rows.get(row);
            return switch (column) {
                case 0 -> TIME.format(Instant.ofEpochMilli(t.timestamp()));
                case 1 -> t.symbol();
                case 2 -> t.quantity();
                case 3 -> t.price().toPlainString();
                case 4 -> t.buyer();
                default -> t.seller();
            };
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JTextField host = new JTextField(args.length > 0 ? args[0] : "localhost");
            JTextField port = new JTextField(args.length > 1 ? args[1] : String.valueOf(Protocol.DEFAULT_PORT));
            JTextField name = new JTextField(args.length > 2 ? args[2] : "");
            JPanel panel = new JPanel(new GridLayout(3, 2, 4, 4));
            panel.add(new JLabel("Host"));
            panel.add(host);
            panel.add(new JLabel("Port"));
            panel.add(port);
            panel.add(new JLabel("Trader name"));
            panel.add(name);
            if (JOptionPane.showConfirmDialog(null, panel, "Connect to stock exchange",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                System.exit(0);
            }
            ClientUI ui = new ClientUI(name.getText().trim());
            try {
                ui.connect(host.getText().trim(), Integer.parseInt(port.getText().trim()));
            } catch (IOException | NumberFormatException e) {
                JOptionPane.showMessageDialog(null, "Cannot connect: " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
            ui.setVisible(true);
        });
    }
}
