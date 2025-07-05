package com.example.trading.core;

import java.time.ZonedDateTime;

/**
 * Represents a single tick data point received from the market.
 * This includes last traded price, volume, timestamp, and instrument token.
 */
public class TickData {
    private final ZonedDateTime timestamp;        // Timestamp of the tick
    private final String instrumentToken;     // Instrument token for the tick
    private final double lastTradedPrice;
    private final long lastTradedVolume;      // Volume traded in this specific tick
    private final long totalVolume;           // Cumulative volume for the day up to this tick
    private final double averageTradePrice;   // Average trade price for the day
    private final MarketDepth marketDepth;    // Optional: Market depth snapshot at the time of this tick

    /**
     * Constructor for TickData.
     *
     * @param timestamp         The time the tick occurred.
     * @param instrumentToken   The token identifying the instrument.
     * @param lastTradedPrice   The price at which the last trade occurred.
     * @param lastTradedVolume  The volume of the last trade.
     * @param totalVolume       The total volume traded for the day so far.
     * @param averageTradePrice The average trade price for the day.
     * @param marketDepth       The market depth at the time of the tick (can be null).
     */
    public TickData(ZonedDateTime timestamp, String instrumentToken, double lastTradedPrice,
                    long lastTradedVolume, long totalVolume, double averageTradePrice,
                    MarketDepth marketDepth) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp cannot be null.");
        }
        if (instrumentToken == null || instrumentToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Instrument token cannot be null or empty.");
        }

        this.timestamp = timestamp;
        this.instrumentToken = instrumentToken;
        this.lastTradedPrice = lastTradedPrice;
        this.lastTradedVolume = lastTradedVolume;
        this.totalVolume = totalVolume;
        this.averageTradePrice = averageTradePrice;
        this.marketDepth = marketDepth; // Can be null if not available or not subscribed
    }

    // Getters
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public String getInstrumentToken() {
        return instrumentToken;
    }

    public double getLastTradedPrice() {
        return lastTradedPrice;
    }

    public long getLastTradedVolume() {
        return lastTradedVolume;
    }

    public long getTotalVolume() {
        return totalVolume;
    }

    public double getAverageTradePrice() {
        return averageTradePrice;
    }

    public MarketDepth getMarketDepth() {
        return marketDepth;
    }

    @Override
    public String toString() {
        return "TickData{" +
               "timestamp=" + timestamp +
               ", instrumentToken='" + instrumentToken + '\'' +
               ", lastTradedPrice=" + lastTradedPrice +
               ", lastTradedVolume=" + lastTradedVolume +
               ", totalVolume=" + totalVolume +
               ", averageTradePrice=" + averageTradePrice +
               ", marketDepth=" + (marketDepth != null ? "available" : "N/A") +
               '}';
    }
}
