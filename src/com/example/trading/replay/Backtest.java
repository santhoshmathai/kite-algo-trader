package com.example.trading.replay;

import com.example.trading.app.*;
import com.example.trading.broker.*;
import com.example.trading.core.Candle;
import com.example.trading.storage.Journal;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Chronological multi-symbol replay. A separate open phase permits immediate protection of entry fills. */
public final class Backtest {
    public static List<Candle> read(Path csv,SessionCalendar calendar,Map<String,Equity> assets)throws IOException{
        List<Candle> bars=new ArrayList<>();String previousKey="";Instant previous=null;
        for(String[] r:Csv.read(csv,"timestamp,instrument,open,high,low,close,volume")){
            if(r.length!=7)throw new IOException("Invalid candle columns");
            Candle c=new Candle(OffsetDateTime.parse(r[0]).atZoneSameInstant(SessionCalendar.ZONE),r[1],Double.parseDouble(r[2]),Double.parseDouble(r[3]),Double.parseDouble(r[4]),Double.parseDouble(r[5]),Long.parseLong(r[6]));
            Instant time=c.getTimestamp().toInstant();LocalDate date=c.getTimestamp().toLocalDate();
            if(!assets.containsKey(r[1])||!calendar.isOpenDate(date)||time.isBefore(calendar.open(date))||!time.isBefore(calendar.close(date))||c.getTimestamp().getSecond()!=0||c.getTimestamp().getNano()!=0)
                throw new IOException("Candle outside permitted instrument/session");
            if(previous!=null&&(time.isBefore(previous)||time.equals(previous)&&r[1].compareTo(previousKey)<=0))throw new IOException("Candles must be sorted by time then instrument with no duplicates");
            previous=time;previousKey=r[1];bars.add(c);
        }
        if(bars.isEmpty())throw new IOException("Empty historical data");return bars;
    }
    public static String hash(Path...paths)throws IOException{
        try{MessageDigest md=MessageDigest.getInstance("SHA-256");for(Path p:paths){md.update(Files.readAllBytes(p));md.update((byte)0);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b));return s.toString();}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static Path run(Settings cfg,Path csv,Path output)throws Exception{
        SessionCalendar calendar=new SessionCalendar(cfg.path("calendar"));Map<String,Equity> assets=Equity.read(cfg.path("instruments"),cfg.symbols);Fees fees=new Fees(cfg.path("fees"));
        List<Candle> bars=read(csv,calendar,assets);String hash=hash(cfg.file,csv,cfg.path("instruments"),cfg.path("calendar"),cfg.path("fees"));
        Path run=output.toAbsolutePath().resolve("backtest-"+UUID.randomUUID());Files.createDirectories(run);
        Files.copy(cfg.file,run.resolve("settings.properties"));Files.writeString(run.resolve("dataset.sha256"),hash+"\n");
        List<String> summary=new ArrayList<>();summary.add("date,net_pnl,attempts,flat,status,data_complete");
        int index=0;double total=0,peak=0,drawdown=0;boolean allComplete=true;
        while(index<bars.size()){
            LocalDate day=bars.get(index).getTimestamp().toLocalDate();fees.estimate(day,"BUY",0);
            Path dayPath=run.resolve(day.toString());Files.createDirectories(dayPath);
            SimBroker broker=new SimBroker(cfg.capital,cfg.slippageBps,cfg.maxParticipation);
            try(Journal journal=new Journal(dayPath.resolve("events.journal"))){
                TradingEngine engine=new TradingEngine(cfg,day,assets,broker,journal,fees,hash);
                Instant last=calendar.open(day);Map<String,Instant> lastSeen=new HashMap<>();boolean complete=true;
                while(index<bars.size()&&bars.get(index).getTimestamp().toLocalDate().equals(day)){
                    Instant time=bars.get(index).getTimestamp().toInstant();List<Candle> batch=new ArrayList<>();while(index<bars.size()&&bars.get(index).getTimestamp().toInstant().equals(time))batch.add(bars.get(index++));
                    // Do not use this bar's close/high/low in sizing at its opening timestamp.
                    broker.time(time.toEpochMilli());
                    for(Candle c:batch){engine.mark(c.getInstrumentToken(),c.getOpen(),c.getOpen(),c.getOpen(),time);broker.open(c);}
                    engine.poll(time);engine.poll(time); // Acknowledge simulated protection/cancellation before range processing.
                    for(Candle c:batch)broker.range(c);
                    Instant closed=time.plusSeconds(60);broker.time(closed.toEpochMilli());
                    for(Candle c:batch)engine.mark(c.getInstrumentToken(),c.getClose(),c.getClose(),c.getClose(),closed);
                    engine.poll(closed);engine.poll(closed);
                    for(Candle c:batch){
                        Instant previous=lastSeen.put(c.getInstrumentToken(),time);
                        if(previous==null&&!time.equals(calendar.open(day))||previous!=null&&!previous.plusSeconds(60).equals(time)){
                            complete=false;engine.halt("Historical data gap: "+c.getInstrumentToken());
                        }
                        engine.closed(c,closed);
                    }
                    engine.poll(closed);last=closed;
                    double equity=total+engine.netPnl();peak=Math.max(peak,equity);drawdown=Math.max(drawdown,peak-equity);
                }
                engine.flatten("End of dataset/session");engine.poll(last);engine.poll(last);
                Instant requiredEnd=day.atTime(cfg.flatten).atZone(SessionCalendar.ZONE).toInstant();
                for(String key:assets.keySet())if(!lastSeen.containsKey(key)||lastSeen.get(key).isBefore(requiredEnd))complete=false;
                allComplete &= complete&&engine.flat();
                Reports.write(dayPath,engine,"BACKTEST",hash);
                summary.add(day+","+engine.netPnl()+","+engine.trades().size()+","+engine.flat()+","+engine.modeStatus()+","+complete);total+=engine.netPnl();
            }
        }
        Files.write(run.resolve("days.csv"),summary);
        Files.writeString(run.resolve("summary.txt"),"BACKTEST - initial capital reset to configured allocation each session\nNet marked P&L: "+total+" INR\nMaximum sampled drawdown: "+drawdown+" INR\nAll observed sessions complete and flat: "+allComplete+"\nInspect days.csv: flat=false or data_complete=false invalidates a completed performance result. Entire missing dates require comparison with the intended research date range.\nCosts are estimates. Target exits observe minute closes; live tick exits can differ.\nDataset/context SHA256: "+hash+"\n");
        return run;
    }
}
