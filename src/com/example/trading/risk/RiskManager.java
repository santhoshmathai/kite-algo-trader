package com.example.trading.risk;

import com.example.trading.order.OrderManager;
import com.example.trading.util.LoggingUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RiskManager {

    private final OrderManager orderManager;
    private final Map<String, Double> stopLosses = new ConcurrentHashMap<>();
    private final Map<String, Double> takeProfits = new ConcurrentHashMap<>();
    private double maxDrawdown = 0.1; // 10% max drawdown
    private double peakPortfolioValue = -1;
    private double currentDrawdown = 0;
    private boolean inRiskMitigationMode = false;

    public RiskManager(OrderManager orderManager) {
        this.orderManager = orderManager;
    }

    public void setStopLoss(String instrumentToken, double stopLoss) {
        stopLosses.put(instrumentToken, stopLoss);
        LoggingUtil.info("Stop-loss for " + instrumentToken + " set to " + stopLoss);
    }

    public void setTakeProfit(String instrumentToken, double takeProfit) {
        takeProfits.put(instrumentToken, takeProfit);
        LoggingUtil.info("Take-profit for " + instrumentToken + " set to " + takeProfit);
    }

    public void checkPositions(String instrumentToken, double lastTradedPrice) {
        if (inRiskMitigationMode) {
            return;
        }

        checkDrawdown();

        OrderManager.PositionDetails position = orderManager.getPosition(instrumentToken);
        if (position == null) {
            return;
        }

        if (position.netQuantity > 0) { // Long position
            if (stopLosses.containsKey(instrumentToken) && lastTradedPrice <= stopLosses.get(instrumentToken)) {
                LoggingUtil.info("Stop-loss triggered for long position on " + instrumentToken + " at price " + lastTradedPrice);
                // Place a market sell order to square off the position
                orderManager.placeOrder(createSquareOffOrderParams("SELL", position.netQuantity, instrumentToken), "regular");
                stopLosses.remove(instrumentToken); // Stop-loss executed, remove it
            } else if (takeProfits.containsKey(instrumentToken) && lastTradedPrice >= takeProfits.get(instrumentToken)) {
                LoggingUtil.info("Take-profit triggered for long position on " + instrumentToken + " at price " + lastTradedPrice);
                // Place a market sell order to square off the position
                orderManager.placeOrder(createSquareOffOrderParams("SELL", position.netQuantity, instrumentToken), "regular");
                takeProfits.remove(instrumentToken); // Take-profit executed, remove it
            }
        } else if (position.netQuantity < 0) { // Short position
            if (stopLosses.containsKey(instrumentToken) && lastTradedPrice >= stopLosses.get(instrumentToken)) {
                LoggingUtil.info("Stop-loss triggered for short position on " + instrumentToken + " at price " + lastTradedPrice);
                // Place a market buy order to square off the position
                orderManager.placeOrder(createSquareOffOrderParams("BUY", -position.netQuantity, instrumentToken), "regular");
                stopLosses.remove(instrumentToken); // Stop-loss executed, remove it
            } else if (takeProfits.containsKey(instrumentToken) && lastTradedPrice <= takeProfits.get(instrumentToken)) {
                LoggingUtil.info("Take-profit triggered for short position on " + instrumentToken + " at price " + lastTradedPrice);
                // Place a market buy order to square off the position
                orderManager.placeOrder(createSquareOffOrderParams("BUY", -position.netQuantity, instrumentToken), "regular");
                takeProfits.remove(instrumentToken); // Take-profit executed, remove it
            }
        }
    }

    private Map<String, Object> createSquareOffOrderParams(String transactionType, int quantity, String instrumentToken) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("tradingsymbol", instrumentToken);
        params.put("exchange", "NSE"); // Make configurable if needed
        params.put("transaction_type", transactionType);
        params.put("quantity", quantity);
        params.put("product", "MIS"); // Make configurable if needed
        params.put("order_type", "MARKET");
        params.put("tag", "RiskManager_SquareOff");
        return params;
    }

    private void checkDrawdown() {
        double currentPortfolioValue = calculateCurrentPortfolioValue();
        if (peakPortfolioValue < 0) {
            peakPortfolioValue = currentPortfolioValue;
        } else {
            peakPortfolioValue = Math.max(peakPortfolioValue, currentPortfolioValue);
        }

        currentDrawdown = (peakPortfolioValue - currentPortfolioValue) / peakPortfolioValue;
        if (currentDrawdown > maxDrawdown) {
            inRiskMitigationMode = true;
            LoggingUtil.warning("Maximum drawdown exceeded. Entering risk mitigation mode.");
            mitigateRisk();
        }
    }

    private void mitigateRisk() {
        LoggingUtil.info("Mitigating risk...");

        // Cancel all open orders
        for (OrderManager.OrderDetails order : orderManager.getAllActiveOrders()) {
            orderManager.cancelOrder(order.orderId, "regular");
        }

        // Square off all open positions
        for (OrderManager.PositionDetails position : orderManager.getAllOpenPositions().values()) {
            if (position.netQuantity > 0) {
                orderManager.placeOrder(createSquareOffOrderParams("SELL", position.netQuantity, position.instrumentToken), "regular");
            } else if (position.netQuantity < 0) {
                orderManager.placeOrder(createSquareOffOrderParams("BUY", -position.netQuantity, position.instrumentToken), "regular");
            }
        }
    }

    private double calculateCurrentPortfolioValue() {
        // This is a simplified calculation. A real implementation would need to fetch account balance and positions value.
        double positionsValue = 0;
        for (OrderManager.PositionDetails position : orderManager.getAllOpenPositions().values()) {
            // This requires getting the last traded price for each position's instrument
            // For simplicity, we'll just use the net quantity and average price for now
            positionsValue += position.netQuantity * position.averageBuyPrice; // This is not accurate, needs LTP
        }
        return 100000 + positionsValue; // Assuming a starting capital of 100000
    }
}
