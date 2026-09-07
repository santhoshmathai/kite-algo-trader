package com.example.trading.core;

/** Explicit identity: tokens are data/position keys; exchange and symbol are order fields. */
public final class Instrument {
    public final String token;
    public final String exchange;
    public final String symbol;
    public final boolean tradable;

    public Instrument(String token, String exchange, String symbol, boolean tradable) {
        if (token == null || token.isBlank() || exchange == null || exchange.isBlank()
                || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Instrument identity must be complete");
        }
        this.token = token;
        this.exchange = exchange;
        this.symbol = symbol;
        this.tradable = tradable;
    }

    public String orderKey() { return exchange + ":" + symbol; }
}
