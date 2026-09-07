package com.example.trading.app;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Allowlisted regular sessions. Missing dates are closed; future/special sessions are not guessed. */
public final class SessionCalendar {
    public static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private final Map<LocalDate,LocalTime[]> sessions=new HashMap<>();
    public SessionCalendar(Path file) throws IOException {
        for(String[] r:Csv.read(file,"date,open,close")) {
            if(r.length!=3) throw new IOException("Invalid session row"); LocalDate d=LocalDate.parse(r[0]);
            LocalTime open=LocalTime.parse(r[1]), close=LocalTime.parse(r[2]);
            if(!open.equals(LocalTime.of(9,15))||!close.isAfter(LocalTime.of(10,15))||close.isAfter(LocalTime.of(15,30))) throw new IOException("Unsupported session");
            if(sessions.put(d,new LocalTime[]{open,close})!=null) throw new IOException("Duplicate session date");
        }
    }
    public boolean isOpenDate(LocalDate d) { return sessions.containsKey(d); }
    public Instant open(LocalDate d) { return at(d,0); }
    public Instant close(LocalDate d) { return at(d,1); }
    private Instant at(LocalDate d,int i) { if(!sessions.containsKey(d)) throw new IllegalArgumentException("No permitted session: "+d); return d.atTime(sessions.get(d)[i]).atZone(ZONE).toInstant(); }
}
