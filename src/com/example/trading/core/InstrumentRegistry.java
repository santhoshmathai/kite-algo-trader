package com.example.trading.core;

import java.util.HashMap;
import java.util.Map;

/** Rejects ambiguous mappings rather than guessing a trading symbol from a token. */
public final class InstrumentRegistry {
    private final Map<String, Instrument> tokens = new HashMap<>();
    private final Map<String, Instrument> symbols = new HashMap<>();

    public synchronized void register(Instrument instrument) {
        if (tokens.containsKey(instrument.token) || symbols.containsKey(instrument.orderKey())) {
            throw new IllegalArgumentException("Duplicate instrument mapping: " + instrument.orderKey());
        }
        tokens.put(instrument.token, instrument);
        symbols.put(instrument.orderKey(), instrument);
    }

    public synchronized Instrument byToken(String token) {
        Instrument result = tokens.get(token);
        if (result == null) throw new IllegalArgumentException("Unknown token: " + token);
        return result;
    }

    public synchronized Instrument forOrder(String exchange, String symbol) {
        Instrument result = symbols.get(exchange + ":" + symbol);
        if (result == null || !result.tradable) {
            throw new IllegalArgumentException("Unknown or non-tradable instrument: " + exchange + ":" + symbol);
        }
        return result;
    }
}
