package com.example.trading.core;

import java.time.*;
import java.util.Set;

/** Regular cash-market session. Holidays are supplied explicitly; special sessions are unsupported. */
public final class TradingSession {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final LocalTime OPEN = LocalTime.of(9, 15);
    public static final LocalTime CLOSE = LocalTime.of(15, 30);
    private final Set<LocalDate> holidays;

    public TradingSession() { this(Set.of()); }
    public TradingSession(Set<LocalDate> holidays) { this.holidays = Set.copyOf(holidays); }

    public boolean isTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    public boolean contains(ZonedDateTime timestamp) {
        ZonedDateTime local = timestamp.withZoneSameInstant(ZONE);
        return isTradingDay(local.toLocalDate()) && !local.toLocalTime().isBefore(OPEN)
                && local.toLocalTime().isBefore(CLOSE);
    }
}
