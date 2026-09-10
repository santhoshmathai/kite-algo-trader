package com.example.trading.app;

import com.example.trading.core.*;
import java.math.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;

/** Equity listing identity is stable across broker token refreshes; only the adapter uses token. */
public final class Equity {
    public final String id, symbol, sector;
    public final long token;
    public final BigDecimal tick;
    public final LocalDate asOf;
    public Equity(String symbol, long token, double tick, String sector, LocalDate asOf) {
        this("NSE",symbol,token,tick,sector,asOf);
    }
    public Equity(String exchange,String symbol, long token, double tick, String sector, LocalDate asOf) {
        if (!symbol.matches("[A-Z0-9&_.-]+") || token <= 0 || !Double.isFinite(tick) || tick <= 0) throw new IllegalArgumentException("Invalid equity");
        if(!exchange.matches("[A-Z]+"))throw new IllegalArgumentException("Invalid exchange");
        this.symbol=symbol; id=exchange+":"+symbol; this.token=token; this.tick=BigDecimal.valueOf(tick); this.sector=sector; this.asOf=asOf;
    }
    public double price(double value, boolean up) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid price");
        return BigDecimal.valueOf(value).divide(tick,0,up?RoundingMode.CEILING:RoundingMode.FLOOR).multiply(tick).doubleValue();
    }
    public Instrument strategyInstrument() { return new Instrument(id,id.split(":")[0],symbol,true); }
    public static Map<String,Equity> read(Path path, Set<String> symbols) throws IOException {
        List<String[]> rows=Csv.read(path, "symbol,token,tick_size,sector,as_of"); Map<String,Equity> result=new TreeMap<>();
        for(String[] r: rows) {
            if(r.length!=5) throw new IOException("Invalid instrument row");
            if(!symbols.contains(r[0])) continue;
            Equity e=new Equity(r[0],Long.parseLong(r[1]),Double.parseDouble(r[2]),r[3],LocalDate.parse(r[4]));
            if(result.put(e.id,e)!=null) throw new IOException("Duplicate listing");
        }
        if(result.size()!=symbols.size()) throw new IOException("Instrument file does not cover configured symbols; run instruments command");
        return result;
    }
}
