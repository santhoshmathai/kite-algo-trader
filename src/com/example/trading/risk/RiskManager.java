package com.example.trading.risk;

import com.example.trading.order.OrderManager;
import java.util.*;

/** Offline risk checks. Exit intent remains active until fill confirmation; no broker-side protection yet. */
public final class RiskManager {
    private final OrderManager orders;
    private final double startingCapital, maximumLoss;
    private final Map<String, Double> stops = new HashMap<>(), targets = new HashMap<>();
    private final Set<String> exitRequested = new HashSet<>();
    private boolean halted;
    public RiskManager(OrderManager orders, double startingCapital, double maximumLoss) {
        if (orders == null || !Double.isFinite(startingCapital) || startingCapital <= 0
                || !Double.isFinite(maximumLoss) || maximumLoss <= 0 || maximumLoss >= startingCapital)
            throw new IllegalArgumentException("Invalid risk budget");
        this.orders = orders; this.startingCapital = startingCapital; this.maximumLoss = maximumLoss;
    }
    public synchronized void setStopLoss(String token, double price) { validate(token, price); stops.put(token, price); }
    public synchronized void setTakeProfit(String token, double price) { validate(token, price); targets.put(token, price); }
    private void validate(String token, double price) {
        orders.getInstrument(token);
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Invalid risk price");
    }
    public synchronized void checkPositions(String token, double lastPrice) {
        validate(token, lastPrice);
        orders.markPrice(token, lastPrice);
        OrderManager.PositionDetails p = orders.getPosition(token);
        if (p == null || p.netQuantity == 0) {
            // Preserve entry protection while an entry order is still awaiting fills.
            boolean pending = orders.getAllActiveOrders().stream().anyMatch(o -> o.instrumentToken.equals(token));
            if (!pending) { stops.remove(token); targets.remove(token); exitRequested.remove(token); }
            return;
        }
        Double stop = stops.get(token), target = targets.get(token);
        boolean stopHit = stop != null && (p.netQuantity > 0 ? lastPrice <= stop : lastPrice >= stop);
        boolean targetHit = target != null && (p.netQuantity > 0 ? lastPrice >= target : lastPrice <= target);
        if (halted || stopHit || targetHit) exitRequested.add(token);
        if (exitRequested.contains(token)) orders.requestExit(token);
    }
    /** Loss from the supplied session capital, including marked P&L of open AND closed positions. */
    public synchronized void checkDrawdown() {
        if (orders.getTotalPnl() <= -maximumLoss) halted = true;
        if (halted) flattenAll(); // Retry remaining exposure after rejected/partial exits.
    }
    public synchronized void flattenAll() {
        halted = true;
        orders.haltEntries();
        for (OrderManager.OrderDetails o : orders.getAllActiveOrders())
            if (!"exit".equals(o.tag)) orders.cancelOrder(o.orderId, "regular");
        for (String token : orders.getAllOpenPositions().keySet()) orders.requestExit(token);
    }
    public synchronized boolean isHalted() { return halted; }
    public synchronized double getEquity() { return startingCapital + orders.getTotalPnl(); }
}
