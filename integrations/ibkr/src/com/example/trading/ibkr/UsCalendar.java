package com.example.trading.ibkr;

import com.example.trading.app.Csv;
import com.example.trading.core.TradingSession;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit dated US regular sessions, including early closes; no weekday-only fallback. */
public final class UsCalendar {
    public static final ZoneId ZONE=ZoneId.of("America/New_York");
    private final Map<LocalDate,LocalTime[]> sessions=new HashMap<>();
    public UsCalendar(Path path)throws IOException{
        for(String[] r:Csv.read(path,"date,open,close")){
            if(r.length!=3)throw new IOException("Invalid US session row");
            LocalDate d=LocalDate.parse(r[0]);LocalTime start=LocalTime.parse(r[1]),end=LocalTime.parse(r[2]);
            if(d.getDayOfWeek()==DayOfWeek.SATURDAY||d.getDayOfWeek()==DayOfWeek.SUNDAY
                ||!start.equals(LocalTime.of(9,30))||!end.isAfter(LocalTime.of(10,30))||end.isAfter(LocalTime.of(16,0))
                ||sessions.put(d,new LocalTime[]{start,end})!=null)throw new IOException("Invalid/duplicate US session");
        }
    }
    public boolean permits(LocalDate d){return sessions.containsKey(d);}
    public ZonedDateTime open(LocalDate d){return d.atTime(times(d)[0]).atZone(ZONE);}
    public ZonedDateTime close(LocalDate d){return d.atTime(times(d)[1]).atZone(ZONE);}
    public TradingSession policy(LocalDate d){LocalTime[] t=times(d);return new TradingSession(Set.of(),ZONE,t[0],t[1]);}
    private LocalTime[] times(LocalDate d){LocalTime[] t=sessions.get(d);if(t==null)throw new IllegalArgumentException("Date not permitted by US calendar");return t;}
}
