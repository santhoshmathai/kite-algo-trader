package com.example.trading.core;

import java.time.*;
import java.util.Set;

/** Regular cash-market session. Holidays are supplied explicitly; special sessions are unsupported. */
public final class TradingSession {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final LocalTime OPEN = LocalTime.of(9, 15);
    public static final LocalTime CLOSE = LocalTime.of(15, 30);
    private final Set<LocalDate> holidays;
    private final ZoneId zone;
    private final LocalTime open, close;

    public TradingSession() { this(Set.of()); }
    public TradingSession(Set<LocalDate> holidays) { this(holidays, ZONE, OPEN, CLOSE); }
    public TradingSession(Set<LocalDate> holidays, ZoneId zone, LocalTime open, LocalTime close) {
        if(zone==null||open==null||close==null||!close.isAfter(open))throw new IllegalArgumentException("Invalid session policy");
        this.holidays=Set.copyOf(holidays);this.zone=zone;this.open=open;this.close=close;
    }
    public ZoneId zone(){return zone;}
    public LocalTime open(){return open;}
    public LocalTime close(){return close;}

    public boolean isTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    public boolean contains(ZonedDateTime timestamp) {
        ZonedDateTime local = timestamp.withZoneSameInstant(zone);
        return isTradingDay(local.toLocalDate()) && !local.toLocalTime().isBefore(open)
                && local.toLocalTime().isBefore(close);
    }
}
