package com.example.trading.app;

import com.example.trading.broker.KiteBroker.Quote;
import com.example.trading.core.Candle;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Cumulative snapshot adapter armed before open. It never emits a still-forming candle. */
public final class LiveBars {
    private static final class State { Candle forming;Instant last,published;long volume; }
    private final Map<String,State> states=new TreeMap<>();
    private final Deque<Candle> ready=new ArrayDeque<>();
    private final LocalDate date;
    private final Instant open,close;
    public LiveBars(LocalDate date,SessionCalendar calendar){this.date=date;open=calendar.open(date);close=calendar.close(date);}
    public void quote(Quote q){
        if(q.time.isBefore(open)||!q.time.isBefore(close))return;
        State s=states.computeIfAbsent(q.instrument,k->new State());
        if(s.last!=null&&q.time.isBefore(s.last))throw new IllegalArgumentException("Out-of-order market snapshot");
        if(s.last!=null&&q.volume<s.volume)throw new IllegalArgumentException("Cumulative market volume decreased");
        Instant minute=q.time.truncatedTo(ChronoUnit.MINUTES);
        if(s.last!=null&&minute.isAfter(s.last.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)))throw new IllegalArgumentException("Market data gap");
        if(s.forming!=null&&!q.time.isBefore(s.forming.getTimestamp().toInstant().plusSeconds(60))){ready.add(s.forming);s.published=s.forming.getTimestamp().toInstant();s.forming=null;}
        if(s.published!=null&&!minute.isAfter(s.published))throw new IllegalArgumentException("Tick arrived after candle publication");
        if(s.last==null&&!minute.equals(open))throw new IllegalArgumentException("Missing opening minute");
        long delta=s.last==null?q.volume:q.volume-s.volume;
        if(delta>0){Candle c=s.forming;
            s.forming=c==null?new Candle(minute.atZone(SessionCalendar.ZONE),q.instrument,q.price,q.price,q.price,q.price,delta)
                    :new Candle(c.getTimestamp(),q.instrument,c.getOpen(),Math.max(c.getHigh(),q.price),Math.min(c.getLow(),q.price),q.price,Math.addExact(c.getVolume(),delta));}
        s.last=q.time;s.volume=q.volume;
    }
    public List<Candle> closeThrough(Instant watermark){
        for(State s:states.values())if(s.forming!=null&&!watermark.isBefore(s.forming.getTimestamp().toInstant().plusSeconds(60))){ready.add(s.forming);s.published=s.forming.getTimestamp().toInstant();s.forming=null;}
        List<Candle> result=new ArrayList<>(ready);ready.clear();result.sort(Comparator.comparing(Candle::getTimestamp).thenComparing(Candle::getInstrumentToken));return result;
    }
}
