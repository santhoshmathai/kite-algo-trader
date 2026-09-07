package com.example.trading.app;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Validated India profile. Paths are relative to the configuration file, never the shell directory. */
public final class Settings {
    public final Properties values = new Properties();
    public final Path file, base;
    public final double capital, tradeRisk, dailyLoss, maxNotional, maxOpenRisk, reward, bufferTicks,
            slippageBps, maxParticipation, maxStopFraction, minStopFraction, maxSpreadBps;
    public final int maxPositions, maxTrades, entryTtlSeconds, staleSeconds, maxSignalDelaySeconds;
    public final boolean shorts, liveEnabled;
    public final LocalTime cutoff, flatten;
    public final Set<String> symbols;
    private static final Set<String> KEYS = Set.of("capital","tradeRisk","dailyLoss","maxNotional","maxOpenRisk","reward",
            "bufferTicks","slippageBps","maxParticipation","maxStopFraction","minStopFraction","maxSpreadBps",
            "maxPositions","maxTrades","entryTtlSeconds","staleSeconds","maxSignalDelaySeconds","shorts","liveEnabled",
            "entryCutoff","flattenAt","symbols","instruments","calendar","fees","state","sessionFile");
    public Settings(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize(); base = this.file.getParent();
        try (Reader r = Files.newBufferedReader(this.file)) { values.load(r); }
        for (String key : values.stringPropertyNames()) if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown setting: " + key);
        capital = number("capital", 10000, 1, 100000000);
        tradeRisk = number("tradeRisk", 25, 1, capital);
        dailyLoss = number("dailyLoss", 100, tradeRisk, capital);
        maxNotional = number("maxNotional", 5000, 1, capital);
        maxOpenRisk = number("maxOpenRisk", 50, tradeRisk, dailyLoss);
        reward = number("reward", 1.5, 0.1, 10);
        bufferTicks = number("bufferTicks", 1, 1, 100);
        slippageBps = number("slippageBps", 5, 0, 100);
        maxParticipation = number("maxParticipation", 0.01, 0.00001, 0.1);
        minStopFraction = number("minStopFraction", 0.001, 0.0001, 0.1);
        maxStopFraction = number("maxStopFraction", 0.03, minStopFraction, 0.2);
        maxSpreadBps = number("maxSpreadBps", 10, 0.1, 100);
        maxPositions = integer("maxPositions", 2, 1, 5); maxTrades = integer("maxTrades", 3, 1, 20);
        entryTtlSeconds = integer("entryTtlSeconds", 15, 1, 120);
        staleSeconds = integer("staleSeconds", 15, 2, 60);
        maxSignalDelaySeconds = integer("maxSignalDelaySeconds", 5, 1, 5);
        shorts = bool("shorts", false); liveEnabled = bool("liveEnabled", false);
        cutoff = LocalTime.parse(values.getProperty("entryCutoff", "10:00"));
        flatten = LocalTime.parse(values.getProperty("flattenAt", "10:15"));
        if (!cutoff.isAfter(LocalTime.of(9,31)) || !flatten.isAfter(cutoff) || flatten.isAfter(LocalTime.of(15,15)))
            throw new IllegalArgumentException("Invalid entry/flatten window");
        symbols = new LinkedHashSet<>();
        for (String symbol : required("symbols").split(",")) {
            symbol = symbol.trim();
            if (!symbol.matches("[A-Z0-9&_.-]+") || !symbols.add(symbol)) throw new IllegalArgumentException("Invalid/duplicate symbol");
        }
        if (symbols.size() > 20) throw new IllegalArgumentException("Maximum 20 equity symbols");
    }
    public Path path(String key) { return base.resolve(required(key)).normalize(); }
    private String required(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + key);
        return value;
    }
    private double number(String key, double fallback, double min, double max) {
        double v = Double.parseDouble(values.getProperty(key, "" + fallback));
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException("Invalid setting: " + key);
        return v;
    }
    private int integer(String key, int fallback, int min, int max) {
        return (int) number(key, fallback, min, max) == number(key, fallback, min, max)
                ? (int)number(key, fallback, min, max) : invalidInteger(key);
    }
    private int invalidInteger(String key) { throw new IllegalArgumentException("Integer required: " + key); }
    private boolean bool(String key, boolean fallback) {
        String s = values.getProperty(key, "" + fallback);
        if (!s.equals("true") && !s.equals("false")) throw new IllegalArgumentException("Boolean required: " + key);
        return Boolean.parseBoolean(s);
    }
    public String fingerprint() {
        try {
            StringBuilder canonical = new StringBuilder();
            for (String k : new TreeSet<>(values.stringPropertyNames())) canonical.append(k).append('=').append(values.getProperty(k)).append('\n');
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for(byte b : digest) hex.append(String.format("%02x", b)); return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
