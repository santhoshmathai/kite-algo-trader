package com.example.trading.data;

import com.example.trading.core.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Completed bars only. Tick snapshots use cumulative-volume deltas, never last-trade quantity. */
public final class TimeSeriesManager {
    private static final int LIMIT = 1000;
    private final TradingSession session;
    private final Map<String, State> states = new HashMap<>();
    private static final class State {
        final Map<Integer, Deque<Candle>> bars = new HashMap<>();
        Candle forming;
        boolean formingComplete;
        ZonedDateTime lastTick, watermark, incompleteMinute;
        long cumulative;
        State() { for (int interval : new int[]{1, 5, 15}) bars.put(interval, new ArrayDeque<>()); }
    }
    public TimeSeriesManager() { this(new TradingSession()); }
    public TimeSeriesManager(TradingSession session) { this.session = Objects.requireNonNull(session); }

    public synchronized void addTick(TickData tick) {
        Objects.requireNonNull(tick, "tick");
        ZonedDateTime time = tick.getTimestamp().withZoneSameInstant(TradingSession.ZONE);
        if (!session.contains(time)) throw new IllegalArgumentException("Tick outside regular session");
        double price = tick.getLastTradedPrice();
        if (!Double.isFinite(price) || price <= 0 || tick.getTotalVolume() < 0)
            throw new IllegalArgumentException("Invalid tick price/volume");
        State s = states.computeIfAbsent(tick.getInstrumentToken(), k -> new State());
        if (s.lastTick == null && !s.bars.get(1).isEmpty())
            throw new IllegalStateException("Do not mix historical and tick replay for an instrument");
        if ((s.lastTick != null && time.isBefore(s.lastTick))
                || (s.watermark != null && time.isBefore(s.watermark)))
            throw new IllegalArgumentException("Late tick: closed data cannot be rewritten");
        boolean newDay = s.lastTick == null || !time.toLocalDate().equals(s.lastTick.toLocalDate());
        if (!newDay && tick.getTotalVolume() < s.cumulative)
            throw new IllegalArgumentException("Cumulative volume decreased within session");
        ZonedDateTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        boolean exactOpen = time.toLocalTime().equals(TradingSession.OPEN);
        boolean continuous = !newDay && !minute.isAfter(s.lastTick.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1));
        if ((newDay && !exactOpen) || (!newDay && !continuous)) s.incompleteMinute = minute;
        long delta = newDay ? (exactOpen ? tick.getTotalVolume() : 0) : tick.getTotalVolume() - s.cumulative;
        closeThrough(s, time);
        if (delta > 0) {
            if (s.forming == null) {
                // Late startup or a missing minute cannot establish a trustworthy candle.
                s.formingComplete = !minute.equals(s.incompleteMinute);
                s.forming = new Candle(minute, tick.getInstrumentToken(), price, price, price, price, delta);
            } else {
                Candle c = s.forming;
                s.forming = new Candle(minute, c.getInstrumentToken(), c.getOpen(),
                        Math.max(c.getHigh(), price), Math.min(c.getLow(), price), price,
                        Math.addExact(c.getVolume(), delta));
            }
        }
        s.lastTick = time;
        s.cumulative = tick.getTotalVolume();
    }

    /** Called by an external clock, including when no tick arrives at a minute boundary. */
    public synchronized void advanceTime(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(TradingSession.ZONE);
        for (State s : states.values()) {
            if (s.watermark != null && local.isBefore(s.watermark))
                throw new IllegalArgumentException("Clock moved backwards");
        }
        for (State s : states.values()) closeThrough(s, local);
    }
    private void closeThrough(State s, ZonedDateTime time) {
        if (s.forming != null && !time.isBefore(s.forming.getTimestamp().plusMinutes(1))) {
            if (s.formingComplete) append(s, s.forming, 1);
            s.forming = null;
        }
        s.watermark = time;
    }

    /** Replay one already-closed historical minute. Never preload future bars into a running strategy. */
    public synchronized void addClosedCandle(Candle candle) {
        validate(candle, 1);
        State s = states.computeIfAbsent(candle.getInstrumentToken(), k -> new State());
        if (s.lastTick != null) throw new IllegalStateException("Do not mix tick and historical replay for an instrument");
        append(s, candle, 1);
    }
    private void validate(Candle c, int interval) {
        if (!session.contains(c.getTimestamp()) || c.getTimestamp().getSecond() != 0
                || c.getTimestamp().getNano() != 0
                || ChronoUnit.MINUTES.between(TradingSession.OPEN, c.getTimestamp().toLocalTime()) % interval != 0
                || c.getTimestamp().toLocalTime().plusMinutes(interval).isAfter(TradingSession.CLOSE))
            throw new IllegalArgumentException("Invalid candle session/alignment");
    }
    private void append(State s, Candle c, int interval) {
        Deque<Candle> series = s.bars.get(interval);
        if (!series.isEmpty() && !c.getTimestamp().isAfter(series.getLast().getTimestamp()))
            throw new IllegalArgumentException("Duplicate or out-of-order candle");
        series.addLast(c);
        while (series.size() > LIMIT) series.removeFirst();
        if (interval == 1) { aggregate(s, c, 5); aggregate(s, c, 15); }
    }
    private void aggregate(State s, Candle last, int interval) {
        long offset = ChronoUnit.MINUTES.between(TradingSession.OPEN, last.getTimestamp().toLocalTime());
        if ((offset + 1) % interval != 0) return;
        ZonedDateTime start = last.getTimestamp().minusMinutes(interval - 1);
        List<Candle> group = new ArrayList<>();
        for (Candle c : s.bars.get(1)) if (!c.getTimestamp().isBefore(start)) group.add(c);
        if (group.size() != interval) return; // Missing minutes are not fabricated.
        for (int i = 0; i < interval; i++)
            if (!group.get(i).getTimestamp().equals(start.plusMinutes(i))) return;
        long volume = 0; double high = 0, low = Double.MAX_VALUE;
        for (Candle c : group) {
            volume = Math.addExact(volume, c.getVolume()); high = Math.max(high, c.getHigh()); low = Math.min(low, c.getLow());
        }
        append(s, new Candle(start, last.getInstrumentToken(), group.get(0).getOpen(), high, low,
                last.getClose(), volume), interval);
    }
    public synchronized Deque<Candle> getOneMinSeries(String token) { return snapshot(token, 1); }
    public synchronized Deque<Candle> getFiveMinSeries(String token) { return snapshot(token, 5); }
    public synchronized Deque<Candle> getFifteenMinSeries(String token) { return snapshot(token, 15); }
    private Deque<Candle> snapshot(String token, int interval) {
        State s = states.get(token);
        return s == null ? new ArrayDeque<>() : new ArrayDeque<>(s.bars.get(interval));
    }
    /** Warm-up only: input must be ordered and nonoverlapping; minute input builds higher intervals. */
    public synchronized void loadHistoricalCandles(String token, List<Candle> candles, int interval) {
        if (interval != 1) throw new IllegalArgumentException("Load one-minute history; higher intervals are derived");
        ZonedDateTime previous = null;
        State s = states.get(token);
        if (s != null && (!s.bars.get(1).isEmpty() || s.lastTick != null))
            throw new IllegalStateException("History loading requires an empty instrument");
        for (Candle c : candles) {
            validate(c, 1);
            if (!token.equals(c.getInstrumentToken()) || (previous != null && !c.getTimestamp().isAfter(previous)))
                throw new IllegalArgumentException("History token/order mismatch");
            previous = c.getTimestamp();
        }
        for (Candle c : candles) addClosedCandle(c);
    }
}
