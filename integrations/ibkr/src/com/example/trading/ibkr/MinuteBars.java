package com.example.trading.ibkr;

import com.example.trading.core.Candle;
import java.time.*;
import java.util.*;

/** Requires all twelve consecutive five-second bars. Gaps never become fabricated candles. */
public final class MinuteBars {
    private long minute=-1,last=-1,volume;
    private int count;
    private double open,high,low,close;
    public Optional<Candle> accept(String instrument,long epoch,double o,double h,double l,double c,long v){
        if(epoch%5!=0||epoch<=last||v<0)throw new IllegalArgumentException("Invalid/reordered five-second bar");
        long m=epoch-epoch%60;
        if(m!=minute){minute=m;count=0;volume=0;open=o;high=h;low=l;}
        if(epoch==minute+count*5){count++;volume=Math.addExact(volume,v);high=Math.max(high,h);low=Math.min(low,l);close=c;}
        else count=-100; // Remain incomplete until a new minute starts.
        last=epoch;
        if(count!=12)return Optional.empty();
        return Optional.of(new Candle(Instant.ofEpochSecond(minute).atZone(UsCalendar.ZONE),instrument,open,high,low,close,volume,UsCalendar.ZONE));
    }
}
