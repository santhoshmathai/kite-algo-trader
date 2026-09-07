package com.example.trading.app;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Dated resident-equity fee estimates per executed order; contract-note rounding may differ. */
public final class Fees {
    private final List<String[]> rows;
    public Fees(Path file) throws IOException { rows=Csv.read(file,"from,to,brokerage_rate,brokerage_cap,stt_sell,exchange_rate,sebi_rate,stamp_buy,gst,ipft_rate");
        for(String[] r:rows) { if(r.length!=10||LocalDate.parse(r[0]).isAfter(LocalDate.parse(r[1]))) throw new IOException("Invalid fee interval");
            for(int i=2;i<10;i++) if(!Double.isFinite(Double.parseDouble(r[i]))||Double.parseDouble(r[i])<0) throw new IOException("Invalid fee value"); }
    }
    public double estimate(LocalDate date,String side,double turnover) {
        if(!Double.isFinite(turnover)||turnover<0) throw new IllegalArgumentException("Invalid turnover");
        String[] match=null; for(String[] r:rows) if(!date.isBefore(LocalDate.parse(r[0]))&&!date.isAfter(LocalDate.parse(r[1]))) { if(match!=null) throw new IllegalArgumentException("Overlapping fee intervals");match=r; }
        if(match==null) throw new IllegalArgumentException("No fee schedule for "+date);
        double[] p=new double[10];for(int i=2;i<10;i++)p[i]=Double.parseDouble(match[i]);
        double base=Math.min(p[3],turnover*p[2])+turnover*(p[5]+p[6]+p[9]);
        return base*(1+p[8])+turnover*("SELL".equals(side)?p[4]:p[7]);
    }
}
