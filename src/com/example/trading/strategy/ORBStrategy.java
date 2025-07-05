package com.example.trading.strategy;

import com.example.trading.core.Candle;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.order.OrderManager; // Assuming OrderManager will handle order placement

import java.time.LocalTime;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Implements an Opening Range Breakout (ORB) strategy.
 * This strategy identifies the high and low of an initial period (e.g., first 15 minutes)
 * and places trades when price breaks out of this range.
 */
public class ORBStrategy {

    private final TimeSeriesManager timeSeriesManager;
    private final OrderManager orderManager; // For placing trades
    private final String instrumentToken;
    private final int openingRangeMinutes; // e.g., 15 for a 15-minute ORB
    private final LocalTime marketOpenTime; // e.g., 09:15 for Indian markets
    private final LocalTime strategyEndTime; // Time to stop initiating new ORB trades

    private double openingRangeHigh = -1;
    private double openingRangeLow = -1;
    private boolean rangeEstablished = false;
    private boolean longTradeTaken = false;
    private boolean shortTradeTaken = false;

    // Store active order IDs for this strategy for the instrument
    private String activeBuyOrderId = null;
    private String activeSellOrderId = null;


    /**
     * Constructor for ORBStrategy.
     *
     * @param timeSeriesManager   The manager for accessing candle data.
     * @param orderManager        The manager for placing orders.
     * @param instrumentToken     The instrument to apply this strategy to.
     * @param openingRangeMinutes Duration of the opening range in minutes (e.g., 15, 30, 60).
     * @param marketOpenTime      The official market open time.
     * @param strategyEndTime     Time after which no new ORB trades are initiated.
     */
    public ORBStrategy(TimeSeriesManager timeSeriesManager, OrderManager orderManager,
                       String instrumentToken, int openingRangeMinutes,
                       LocalTime marketOpenTime, LocalTime strategyEndTime) {
        this.timeSeriesManager = timeSeriesManager;
        this.orderManager = orderManager;
        this.instrumentToken = instrumentToken;
        this.openingRangeMinutes = openingRangeMinutes;
        this.marketOpenTime = marketOpenTime;
        this.strategyEndTime = strategyEndTime;
        System.out.println("ORBStrategy initialized for " + instrumentToken +
                           " with " + openingRangeMinutes + "-min range.");
    }

    /**
     * Evaluates the strategy based on the latest candle data.
     * This method should be called when a new candle (relevant to the strategy, e.g., 1-min or 5-min) is available.
     */
    public void evaluate() {
        // Get the 1-minute candles for the instrument
        // Strategies often work on 1-min or 5-min candles for entry signals.
        Deque<Candle> oneMinCandles = timeSeriesManager.getOneMinSeries(instrumentToken);
        if (oneMinCandles.isEmpty()) {
            // System.out.println("ORB ("+instrumentToken+"): No 1-min candles available yet.");
            return;
        }

        Candle lastCandle = oneMinCandles.getLast();
        LocalTime lastCandleTime = lastCandle.getTimestamp().toLocalTime();

        // 1. Establish Opening Range
        if (!rangeEstablished && lastCandleTime.isAfter(marketOpenTime.plusMinutes(openingRangeMinutes))) {
            establishOpeningRange(oneMinCandles);
        }

        // 2. Check for Breakouts if range is established and within trading time
        if (rangeEstablished && !lastCandleTime.isAfter(strategyEndTime)) {
            checkForBreakout(lastCandle);
        }

        // 3. Potentially manage open positions (e.g., stop-loss, target)
        // This part is more complex and would involve tracking open orders and positions.
        // For a skeleton, we'll focus on entry signals.
    }

    /**
     * Establishes the opening range (high and low) using candles within the defined opening period.
     *
     * @param candles Deque of 1-minute candles for the instrument.
     */
    private void establishOpeningRange(Deque<Candle> candles) {
        LocalTime openingRangeEndTime = marketOpenTime.plusMinutes(openingRangeMinutes);
        double high = Double.MIN_VALUE;
        double low = Double.MAX_VALUE;
        boolean foundCandlesInRange = false;

        for (Candle candle : candles) {
            LocalTime candleTime = candle.getTimestamp().toLocalTime();
            // Ensure candle is within the defined opening range period
            if (!candleTime.isBefore(marketOpenTime) && candleTime.isBefore(openingRangeEndTime)) {
                if (candle.getHigh() > high) {
                    high = candle.getHigh();
                }
                if (candle.getLow() < low) {
                    low = candle.getLow();
                }
                foundCandlesInRange = true;
            }
        }

        if (foundCandlesInRange) {
            this.openingRangeHigh = high;
            this.openingRangeLow = low;
            this.rangeEstablished = true;
            System.out.println("ORB (" + instrumentToken + "): Range established. High: " + openingRangeHigh + ", Low: " + openingRangeLow +
                               " at " + LocalTime.now());
        } else {
            System.out.println("ORB ("+instrumentToken+"): Could not establish opening range. No candles found in the defined period.");
        }
    }

    /**
     * Checks if the last candle's price breaks out of the established opening range.
     *
     * @param lastCandle The most recent candle.
     */
    private void checkForBreakout(Candle lastCandle) {
        if (openingRangeHigh <= 0 || openingRangeLow <= 0) {
            System.out.println("ORB (" + instrumentToken + "): Opening range not valid for breakout check.");
            return;
        }

        // Check for Long Breakout
        if (!longTradeTaken && lastCandle.getClose() > openingRangeHigh) {
            System.out.println("ORB (" + instrumentToken + "): Long breakout detected! Price: " + lastCandle.getClose() +
                               " > Range High: " + openingRangeHigh);
            // TODO: Place Buy Order
            // Example: orderManager.placeBuyOrder(instrumentToken, quantity, lastCandle.getClose(), "ORB_LONG");
            Map<String, Object> orderParams = createOrderParams("BUY", 1, lastCandle.getClose(), "LIMIT"); // Example: 1 share
            activeBuyOrderId = orderManager.placeOrder(orderParams, "regular"); // Assuming 'regular' variety
            if (activeBuyOrderId != null) {
                System.out.println("ORB (" + instrumentToken + "): Buy order placed. Order ID: " + activeBuyOrderId);
                longTradeTaken = true; // Prevent multiple trades in the same direction
                // Potentially cancel any pending short entry orders if applicable
                // if (activeSellOrderId != null) orderManager.cancelOrder(activeSellOrderId, "regular");
            } else {
                System.out.println("ORB (" + instrumentToken + "): Failed to place buy order.");
            }
        }
        // Check for Short Breakout
        else if (!shortTradeTaken && lastCandle.getClose() < openingRangeLow) {
            System.out.println("ORB (" + instrumentToken + "): Short breakout detected! Price: " + lastCandle.getClose() +
                               " < Range Low: " + openingRangeLow);
            // TODO: Place Sell Order (Short)
            // Example: orderManager.placeSellOrder(instrumentToken, quantity, lastCandle.getClose(), "ORB_SHORT");
            Map<String, Object> orderParams = createOrderParams("SELL", 1, lastCandle.getClose(), "LIMIT"); // Example: 1 share
            activeSellOrderId = orderManager.placeOrder(orderParams, "regular");
             if (activeSellOrderId != null) {
                System.out.println("ORB (" + instrumentToken + "): Sell order placed. Order ID: " + activeSellOrderId);
                shortTradeTaken = true; // Prevent multiple trades in the same direction
                // Potentially cancel any pending long entry orders
                // if (activeBuyOrderId != null) orderManager.cancelOrder(activeBuyOrderId, "regular");
            } else {
                System.out.println("ORB (" + instrumentToken + "): Failed to place sell order.");
            }
        }
    }

    private Map<String, Object> createOrderParams(String transactionType, int quantity, double price, String orderType) {
        Map<String, Object> params = new HashMap<>();
        params.put("tradingsymbol", this.instrumentToken); // This should be the actual trading symbol, not the Kite token if they differ for orders
        params.put("exchange", "NSE"); // Example, make configurable
        params.put("transaction_type", transactionType); // "BUY" or "SELL"
        params.put("quantity", quantity);
        params.put("product", "MIS"); // Intraday product, make configurable
        params.put("order_type", orderType); // "LIMIT", "MARKET", "SL", "SL-M"

        if ("LIMIT".equals(orderType) || "SL".equals(orderType)) {
            params.put("price", price);
        }
        // For SL orders, trigger_price might also be needed.
        // params.put("trigger_price", price); // If SL or SL-M

        // These are just example parameters. Refer to Kite Connect documentation for full list.
        // params.put("tag", "ORBStrategy"); // Optional tag
        return params;
    }


    /**
     * Resets the strategy state, typically at the end of the day or when parameters change.
     */
    public void reset() {
        this.openingRangeHigh = -1;
        this.openingRangeLow = -1;
        this.rangeEstablished = false;
        this.longTradeTaken = false;
        this.shortTradeTaken = false;
        this.activeBuyOrderId = null;
        this.activeSellOrderId = null;
        System.out.println("ORBStrategy for " + instrumentToken + " has been reset.");
    }

    // Getters for state (optional, for monitoring or external logic)
    public boolean isRangeEstablished() {
        return rangeEstablished;
    }

    public double getOpeningRangeHigh() {
        return openingRangeHigh;
    }

    public double getOpeningRangeLow() {
        return openingRangeLow;
    }
}
