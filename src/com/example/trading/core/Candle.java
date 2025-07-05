package com.example.trading.core;

import java.time.ZonedDateTime;

/**
 * Represents a single price candle (OHLCV) for a specific time period.
 * Includes Open, High, Low, Close, Volume, and Timestamp.
 */
public class Candle {
    private final ZonedDateTime timestamp; // Timestamp marks the beginning of the candle period
    private final double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private final String instrumentToken; // Instrument token this candle belongs to

    /**
     * Constructor for creating a new Candle.
     * Typically, when a candle is first formed from a tick, open, high, low, and close might be the same.
     *
     * @param timestamp The start time of the candle period.
     * @param instrumentToken The instrument token.
     * @param open The opening price.
     * @param high The highest price during the period.
     * @param low The lowest price during the period.
     * @param close The closing price.
     * @param volume The total volume traded during the period.
     */
    public Candle(ZonedDateTime timestamp, String instrumentToken, double open, double high, double low, double close, long volume) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp cannot be null.");
        }
        if (instrumentToken == null || instrumentToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Instrument token cannot be null or empty.");
        }
        this.timestamp = timestamp;
        this.instrumentToken = instrumentToken;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    // Getters
    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public String getInstrumentToken() {
        return instrumentToken;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    // Setters for fields that can change during candle formation (e.g., aggregating ticks)
    public void setHigh(double high) {
        this.high = high;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public void addVolume(long volume) {
        this.volume += volume;
    }

    /**
     * Updates the candle with a new price and volume (typically from a new tick).
     *
     * @param price The price of the new tick.
     * @param tradedVolume The volume of the new tick.
     */
    public void update(double price, long tradedVolume) {
        if (price > this.high) {
            this.high = price;
        }
        if (price < this.low) {
            this.low = price;
        }
        this.close = price;
        this.volume += tradedVolume;
    }


    @Override
    public String toString() {
        return "Candle{" +
               "timestamp=" + timestamp +
               ", instrumentToken='" + instrumentToken + '\'' +
               ", open=" + open +
               ", high=" + high +
               ", low=" + low +
               ", close=" + close +
               ", volume=" + volume +
               '}';
    }
}
