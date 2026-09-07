package com.example.trading.order;

import com.example.trading.core.*;
import java.util.*;

/** Offline order ledger. Submissions never contact a broker or imply a fill. All updates are cumulative. */
public final class OrderManager {
    private final InstrumentRegistry instruments;
    private final Map<String, OrderDetails> orders = new LinkedHashMap<>();
    private final Map<String, PositionDetails> positions = new LinkedHashMap<>();
    private long sequence;
    private boolean entriesEnabled = true;

    public static final class OrderDetails {
        public final String orderId, instrumentToken, tradingSymbol, exchange, transactionType, orderType, productType, tag;
        public final int quantity;
        public final double price;
        public String status = "OPEN";
        public int filledQuantity;
        public double averageFillPrice;
        private OrderDetails(String id, Instrument instrument, String side, int qty, double price, String type, String tag) {
            orderId = id; instrumentToken = instrument.token; tradingSymbol = instrument.symbol;
            exchange = instrument.exchange; transactionType = side; quantity = qty;
            this.price = price; orderType = type; productType = "MIS"; this.tag = tag;
        }
        private OrderDetails(OrderDetails o) {
            this(o.orderId, new Instrument(o.instrumentToken, o.exchange, o.tradingSymbol, true),
                    o.transactionType, o.quantity, o.price, o.orderType, o.tag);
            status = o.status; filledQuantity = o.filledQuantity; averageFillPrice = o.averageFillPrice;
        }
        public boolean isTerminal() { return Set.of("COMPLETE", "CANCELLED", "REJECTED").contains(status); }
    }
    public static final class PositionDetails {
        public final String instrumentToken;
        public int netQuantity;
        public double averageBuyPrice, averageSellPrice, realizedPnl, unrealizedPnl;
        private double lastPrice;
        private PositionDetails(String token) { instrumentToken = token; }
        private PositionDetails(PositionDetails p) {
            instrumentToken = p.instrumentToken; netQuantity = p.netQuantity;
            averageBuyPrice = p.averageBuyPrice; averageSellPrice = p.averageSellPrice;
            realizedPnl = p.realizedPnl; unrealizedPnl = p.unrealizedPnl; lastPrice = p.lastPrice;
        }
    }
    public OrderManager(InstrumentRegistry instruments) { this.instruments = Objects.requireNonNull(instruments); }
    public Instrument getInstrument(String token) { return instruments.byToken(token); }
    public synchronized void haltEntries() { entriesEnabled = false; }
    public synchronized boolean areEntriesEnabled() { return entriesEnabled; }

    public synchronized String placeOrder(Map<String, Object> params, String variety) {
        return submit(params, variety, false);
    }
    private String submit(Map<String, Object> params, String variety, boolean exit) {
        if (!exit && !entriesEnabled) throw new IllegalStateException("Entries halted");
        if (!exit && "exit".equals(params.get("tag"))) throw new IllegalArgumentException("Exit tag is reserved");
        if (!"regular".equals(variety) || !"MIS".equals(params.get("product")))
            throw new IllegalArgumentException("Foundation supports regular MIS orders only");
        Instrument instrument = instruments.forOrder((String) params.get("exchange"), (String) params.get("tradingsymbol"));
        if (params.containsKey("instrument_token") && !instrument.token.equals(params.get("instrument_token")))
            throw new IllegalArgumentException("Order symbol/token mismatch");
        String side = (String) params.get("transaction_type"), type = (String) params.get("order_type");
        if (!("BUY".equals(side) || "SELL".equals(side)) || !("LIMIT".equals(type) || "MARKET".equals(type)))
            throw new IllegalArgumentException("Unsupported side/type");
        Object quantity = params.get("quantity");
        if (!(quantity instanceof Integer) || (Integer) quantity <= 0)
            throw new IllegalArgumentException("Positive integer quantity required");
        Object rawPrice = params.getOrDefault("price", 0.0);
        if (!(rawPrice instanceof Number)) throw new IllegalArgumentException("Numeric price required");
        double price = ((Number) rawPrice).doubleValue();
        if (!Double.isFinite(price) || price < 0 || ("LIMIT".equals(type) && price == 0))
            throw new IllegalArgumentException("Invalid price");
        String id = "sim-" + (++sequence);
        orders.put(id, new OrderDetails(id, instrument, side, (Integer) quantity, price, type,
                (String) params.getOrDefault("tag", "entry")));
        return id;
    }
    /** One outstanding exit per instrument; cancel pending entries before sizing the close. */
    public synchronized String requestExit(String token) {
        for (OrderDetails o : orders.values())
            if (!o.isTerminal() && o.instrumentToken.equals(token) && "exit".equals(o.tag)) return o.orderId;
        for (OrderDetails o : orders.values())
            if (!o.isTerminal() && o.instrumentToken.equals(token)) cancelOrder(o.orderId, "regular");
        PositionDetails p = positions.get(token);
        if (p == null || p.netQuantity == 0) return null;
        Instrument i = instruments.byToken(token);
        return submit(Map.of("exchange", i.exchange, "tradingsymbol", i.symbol,
                "transaction_type", p.netQuantity > 0 ? "SELL" : "BUY", "quantity", Math.abs(p.netQuantity),
                "product", "MIS", "order_type", "MARKET", "tag", "exit"), "regular", true);
    }
    public synchronized String modifyOrder(String id, Map<String, Object> params, String variety) {
        throw new UnsupportedOperationException("Order modification is not implemented in offline foundation");
    }
    public synchronized String cancelOrder(String id, String variety) {
        if (!"regular".equals(variety)) throw new IllegalArgumentException("Unsupported variety");
        OrderDetails o = requireOrder(id);
        if (!o.isTerminal()) o.status = "CANCELLED"; // Synchronous simulated cancellation only.
        return id;
    }

    /** Broker-style cumulative quantity AND cumulative average price; deduplicates cumulative fills. */
    public synchronized void updateOrderStatus(String id, String status, int cumulativeQty, double cumulativeAverage) {
        OrderDetails o = requireOrder(id);
        if (status == null) throw new IllegalArgumentException("Status required");
        String normalized = status.toUpperCase(Locale.ROOT);
        if ("FILLED".equals(normalized)) normalized = "COMPLETE";
        if (!Set.of("OPEN", "PENDING", "TRIGGER PENDING", "COMPLETE", "CANCELLED", "REJECTED").contains(normalized))
            throw new IllegalArgumentException("Unsupported status: " + status);
        if (cumulativeQty < 0 || cumulativeQty > o.quantity)
            throw new IllegalArgumentException("Invalid cumulative fill quantity");
        if (cumulativeQty < o.filledQuantity) return; // Delayed older snapshot.
        if ("COMPLETE".equals(normalized) && cumulativeQty != o.quantity)
            throw new IllegalArgumentException("Complete order must be fully filled");
        if (cumulativeQty > 0 && (!Double.isFinite(cumulativeAverage) || cumulativeAverage <= 0))
            throw new IllegalArgumentException("Invalid cumulative fill price");
        int delta = cumulativeQty - o.filledQuantity;
        if (delta == 0 && cumulativeQty > 0 && Math.abs(cumulativeAverage - o.averageFillPrice) > 1e-8)
            throw new IllegalArgumentException("Fill-price correction requires reconciliation");
        if (delta > 0) {
            double incrementalPrice = (cumulativeQty * cumulativeAverage - o.filledQuantity * o.averageFillPrice) / delta;
            if (!Double.isFinite(incrementalPrice) || incrementalPrice <= 0)
                throw new IllegalArgumentException("Invalid incremental fill value");
            updatePosition(o, delta, incrementalPrice);
            o.filledQuantity = cumulativeQty; o.averageFillPrice = cumulativeAverage;
        }
        // Never reopen a terminal order on a delayed OPEN notification.
        if (!o.isTerminal() || "COMPLETE".equals(normalized)) o.status = normalized;
    }
    private void updatePosition(OrderDetails o, int qty, double price) {
        PositionDetails p = positions.computeIfAbsent(o.instrumentToken, PositionDetails::new);
        int signedQty = "BUY".equals(o.transactionType) ? qty : -qty;
        int before = p.netQuantity;
        int after = Math.addExact(before, signedQty);
        if (after == Integer.MIN_VALUE) throw new IllegalArgumentException("Position exceeds supported quantity");
        double average = before >= 0 ? p.averageBuyPrice : p.averageSellPrice;
        if (before == 0 || Integer.signum(before) == Integer.signum(signedQty)) {
            average = (Math.abs((double) before) * average + qty * price) / Math.abs((double) after);
        } else {
            int closed = Math.min(Math.abs(before), qty);
            p.realizedPnl += closed * (price - average) * Integer.signum(before);
            if (after == 0) average = 0;
            else if (Integer.signum(after) != Integer.signum(before)) average = price;
        }
        p.netQuantity = after;
        p.averageBuyPrice = after > 0 ? average : 0;
        p.averageSellPrice = after < 0 ? average : 0;
        if (p.lastPrice == 0) p.lastPrice = price;
        revalue(p);
    }
    public synchronized void markPrice(String token, double price) {
        instruments.byToken(token);
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Invalid mark");
        PositionDetails p = positions.get(token);
        if (p != null) { p.lastPrice = price; revalue(p); }
    }
    private void revalue(PositionDetails p) {
        double average = p.netQuantity > 0 ? p.averageBuyPrice : p.averageSellPrice;
        p.unrealizedPnl = p.netQuantity * (p.lastPrice - average);
    }
    private OrderDetails requireOrder(String id) {
        OrderDetails o = orders.get(id);
        if (o == null) throw new IllegalArgumentException("Unknown order: " + id);
        return o;
    }
    public synchronized OrderDetails getOrderDetails(String id) { return new OrderDetails(requireOrder(id)); }
    public synchronized List<OrderDetails> getAllActiveOrders() {
        List<OrderDetails> result = new ArrayList<>();
        for (OrderDetails o : orders.values()) if (!o.isTerminal()) result.add(new OrderDetails(o));
        return result;
    }
    public synchronized PositionDetails getPosition(String token) {
        PositionDetails p = positions.get(token);
        return p == null ? null : new PositionDetails(p);
    }
    public synchronized Map<String, PositionDetails> getAllOpenPositions() {
        Map<String, PositionDetails> result = new LinkedHashMap<>();
        for (PositionDetails p : positions.values()) if (p.netQuantity != 0) result.put(p.instrumentToken, new PositionDetails(p));
        return Collections.unmodifiableMap(result);
    }
    /** Includes realized profit from positions already closed; excludes costs in this foundation. */
    public synchronized double getTotalPnl() {
        return positions.values().stream().mapToDouble(p -> p.realizedPnl + p.unrealizedPnl).sum();
    }
}
