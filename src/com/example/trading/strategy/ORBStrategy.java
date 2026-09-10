package com.example.trading.strategy;

import com.example.trading.core.*;
import java.time.*;
import java.util.*;

/** Baseline ORB signal generator. Receives one completed minute at a time; never places orders. */
public final class ORBStrategy {
    public static final class Signal {
        public final Instrument instrument;
        public final ZonedDateTime availableAt;
        public final String side;
        public final double referencePrice, stopPrice;
        private Signal(Instrument instrument, ZonedDateTime availableAt, String side, double referencePrice, double stopPrice) {
            this.instrument = instrument; this.availableAt = availableAt; this.side = side;
            this.referencePrice = referencePrice; this.stopPrice = stopPrice;
        }
    }
    private final Instrument instrument;
    private final TradingSession session;
    private final int rangeMinutes;
    private final LocalTime entryCutoff;
    private final double buffer;
    private final boolean allowShorts;
    private LocalDate date;
    private ZonedDateTime lastProcessed;
    private int openingBars;
    private boolean rangeValid = true, signalled;
    private double rangeHigh, rangeLow;

    public ORBStrategy(Instrument instrument, TradingSession session, int rangeMinutes,
                       LocalTime entryCutoff, double buffer, boolean allowShorts) {
        if (instrument == null || !instrument.tradable || session == null || rangeMinutes < 1 || rangeMinutes > 60
                || entryCutoff == null || !entryCutoff.isAfter(session.open().plusMinutes(rangeMinutes + 1))
                || entryCutoff.isAfter(session.close()) || !Double.isFinite(buffer) || buffer < 0)
            throw new IllegalArgumentException("Invalid ORB parameters");
        this.instrument = instrument; this.session = session; this.rangeMinutes = rangeMinutes;
        this.entryCutoff = entryCutoff; this.buffer = buffer; this.allowShorts = allowShorts;
    }

    /** availableAt must be the actual replay/live observation time, never earlier than candle close. */
    public Optional<Signal> onClosedCandle(Candle candle, ZonedDateTime availableAt) {
        ZonedDateTime start = candle.getTimestamp().withZoneSameInstant(session.zone());
        ZonedDateTime now = availableAt.withZoneSameInstant(session.zone());
        if (!instrument.token.equals(candle.getInstrumentToken()) || !session.contains(start)
                || start.getSecond() != 0 || start.getNano() != 0 || now.isBefore(start.plusMinutes(1)))
            throw new IllegalArgumentException("Invalid or unfinished signal candle");
        if (lastProcessed != null && start.isBefore(lastProcessed))
            throw new IllegalArgumentException("Out-of-order strategy input");
        if (start.equals(lastProcessed)) return Optional.empty();
        if (!start.toLocalDate().equals(date)) {
            date = start.toLocalDate(); openingBars = 0; rangeValid = true; signalled = false;
            rangeHigh = 0; rangeLow = Double.MAX_VALUE;
        }
        lastProcessed = start;
        LocalTime endOfRange = session.open().plusMinutes(rangeMinutes);
        if (start.toLocalTime().isBefore(endOfRange)) {
            if (!start.toLocalTime().equals(session.open().plusMinutes(openingBars))) rangeValid = false;
            openingBars++;
            rangeHigh = Math.max(rangeHigh, candle.getHigh());
            rangeLow = Math.min(rangeLow, candle.getLow());
            return Optional.empty();
        }
        // Delayed history may warm up state, but must not trigger an order at a stale price.
        if (now.isAfter(start.plusMinutes(1).plusSeconds(5)) || !rangeValid || openingBars != rangeMinutes || signalled
                || !now.toLocalDate().equals(date) || !now.toLocalTime().isBefore(entryCutoff)) return Optional.empty();
        String side = null;
        if (candle.getClose() > rangeHigh + buffer) side = "BUY";
        else if (allowShorts && candle.getClose() < rangeLow - buffer) side = "SELL";
        if (side == null) return Optional.empty();
        signalled = true; // One signal/attempt per day, including skipped or unfilled attempts.
        return Optional.of(new Signal(instrument, now, side, candle.getClose(), "BUY".equals(side) ? rangeLow : rangeHigh));
    }
    public boolean isOpeningRangeEstablished() { return rangeValid && openingBars == rangeMinutes; }
    public double getOpeningRangeHigh() { return rangeHigh; }
    public double getOpeningRangeLow() { return rangeLow; }
}
