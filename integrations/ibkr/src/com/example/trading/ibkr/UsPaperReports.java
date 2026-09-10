package com.example.trading.ibkr;

import com.example.trading.app.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Separate USD reports; never feeds the India INR paper-summary command. */
public final class UsPaperReports {
    private static void write(Path path,String text)throws Exception{
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(tmp,text);
        Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static void write(Path directory,TradingEngine engine,List<String> executions,String connection)throws Exception{
        Files.createDirectories(directory);
        StringBuilder trades=new StringBuilder("symbol,side,entry_filled,entry_average,remaining,gross_usd,estimated_costs_usd,estimated_net_usd,reason\n");
        StringBuilder orders=new StringBuilder("order_id,tag,symbol,side,type,quantity,filled,average,status,pending\n");
        double gross=0,cost=0;int open=0;
        for(TradingEngine.Trade t:engine.trades()){
            var entry=engine.tracked(t.entryTag);double g=engine.tradeGross(t),f=engine.tradeCosts(t);gross+=g;cost+=f;open+=engine.remaining(t);
            trades.append(String.join(",",t.instrument,t.side,""+entry.filled,""+entry.average,""+engine.remaining(t),""+g,""+f,""+(g-f),clean(t.reason))).append('\n');
            List<String> tags=new ArrayList<>();tags.add(t.entryTag);tags.addAll(t.exits);
            for(String tag:tags){var o=engine.tracked(tag);var r=o.request;orders.append(String.join(",",o.brokerId,tag,r.instrument,r.side,r.type,""+r.quantity,""+o.filled,""+o.average,o.status,o.pending)).append('\n');}
        }
        write(directory.resolve("trades.csv"),trades.toString());write(directory.resolve("orders.csv"),orders.toString());
        write(directory.resolve("executions.csv"),"execution_id,order_id,tag,instrument,side,shares,price,broker_time,actual_commission_and_fees,commission_currency\n"+String.join("\n",executions)+"\n");
        write(directory.resolve("decisions.log"),String.join("\n",engine.messages())+"\n");
        Properties p=new Properties();p.setProperty("date",engine.date.toString());p.setProperty("currency","USD");p.setProperty("mode",connection.equals("OFFLINE_DEMO")?"OFFLINE_DEMO":"IBKR_PAPER");p.setProperty("connection",connection);
        p.setProperty("updated",Instant.now().toString());p.setProperty("status",engine.modeStatus());p.setProperty("reason",engine.reason());
        p.setProperty("capital",""+engine.config.capital);p.setProperty("gross",""+gross);p.setProperty("estimatedCosts",""+cost);p.setProperty("estimatedNet",""+(gross-cost));
        p.setProperty("remainingShares",""+open);p.setProperty("confirmedFlat",""+engine.flat());p.setProperty("fingerprint",engine.config.fingerprint());
        java.io.StringWriter writer=new java.io.StringWriter();p.store(writer,"USD paper results: cost allowance, not actual broker commissions");write(directory.resolve("session.properties"),writer.toString());
        write(directory.resolve("status.txt"),"US PAPER | "+engine.date+" | "+engine.modeStatus()+" | "+connection+"\nUpdated: "+Instant.now()+"\nEstimated net USD: "+String.format(Locale.ROOT,"%.2f",gross-cost)+"\nRemaining shares: "+open+" | Confirmed flat: "+engine.flat()+"\n"+engine.reason()+"\nBroker statement is authoritative; stale/disconnected marks are not current valuations.\n");
    }
    static String clean(String value){return value.replace(',',';').replace('\n',' ');}
    public static void summary(Path root,LocalDate from,LocalDate to)throws Exception{
        if(to.isBefore(from))throw new IllegalArgumentException("Invalid date interval");
        int sessions=0;double net=0;String fingerprint=null;
        try(var files=Files.walk(root)){for(Path path:files.filter(p->p.getFileName().toString().equals("session.properties")).sorted().toList()){
            Properties p=new Properties();try(var r=Files.newBufferedReader(path)){p.load(r);}LocalDate date=LocalDate.parse(p.getProperty("date"));
            if(date.isBefore(from)||date.isAfter(to))continue;
            if(!p.getProperty("mode","").equals("IBKR_PAPER")||!p.getProperty("currency","").equals("USD"))continue;
            if(fingerprint==null)fingerprint=p.getProperty("fingerprint");if(!Objects.equals(fingerprint,p.getProperty("fingerprint")))throw new IllegalArgumentException("Different risk profiles; summarize separately");
            double value=Double.parseDouble(p.getProperty("estimatedNet"));net+=value;sessions++;
            System.out.printf(Locale.ROOT,"%s estimated net USD %.2f | %s | confirmed flat=%s%n",date,value,p.getProperty("status"),p.getProperty("confirmedFlat"));
        }}
        System.out.printf(Locale.ROOT,"Recorded sessions: %d; estimated net USD %.2f. Missing days are not zero-profit days. Non-flat results include marked positions.%n",sessions,net);
    }
}
