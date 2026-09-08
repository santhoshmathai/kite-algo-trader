package com.example.trading.core;

import java.time.ZonedDateTime;
import java.time.ZoneId;

/** Immutable OHLCV snapshot; timestamp is the start of the interval in India time. */
public final class Candle {
    private final ZonedDateTime timestamp;
    private final String instrumentToken;
    private final double open, high, low, close;
    private final long volume;

    public Candle(ZonedDateTime timestamp, String instrumentToken, double open, double high,
                  double low, double close, long volume) {
        this(timestamp,instrumentToken,open,high,low,close,volume,TradingSession.ZONE);
    }
    /** Explicit market zone; the existing constructor retains India normalization. */
    public Candle(ZonedDateTime timestamp, String instrumentToken, double open, double high,
                  double low, double close, long volume, ZoneId zone) {
        if (timestamp == null || instrumentToken == null || instrumentToken.isBlank())
            throw new IllegalArgumentException("Candle identity is required");
        if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low)
                || !Double.isFinite(close) || low <= 0 || high < low
                || open < low || open > high || close < low || close > high || volume < 0)
            throw new IllegalArgumentException("Invalid OHLCV");
        this.timestamp = timestamp.withZoneSameInstant(java.util.Objects.requireNonNull(zone));
        this.instrumentToken = instrumentToken;
        this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
    }
    public ZonedDateTime getTimestamp() { return timestamp; }
    public String getInstrumentToken() { return instrumentToken; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    @Override public String toString() {
        return String.format(java.util.Locale.ROOT, "%s %s O=%.2f H=%.2f L=%.2f C=%.2f V=%d",
                timestamp, instrumentToken, open, high, low, close, volume);
    }
}
