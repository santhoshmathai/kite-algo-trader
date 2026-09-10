package com.example.trading.ibkr;

import com.example.trading.app.*;
import com.example.trading.core.Candle;
import com.example.trading.storage.Journal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.*;

/** Single owner event loop: market callbacks never place orders themselves. */
public final class IbkrPaperRuntime {
    static String context(IbkrSettings cfg,UsPaperProfile profile)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        String text="IBKR_PAPER_USD_v1|"+cfg.expectedAccount+"|"+cfg.clientId+"|"+profile.risk.fingerprint()+"|"+Files.readString(cfg.calendar)+"|"+Files.readString(profile.risk.path("instruments"));
        return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static Path accountRoot(IbkrSettings cfg,UsPaperProfile profile){
        if(!cfg.expectedAccount.matches("DU[0-9]+"))throw new IllegalArgumentException("Set exact DU paper account first");
        return profile.risk.path("state").resolve(cfg.expectedAccount);
    }
    public static void run(IbkrSettings cfg,UsPaperProfile profile,boolean armed)throws Exception{
        IbkrPaperBroker.requirePaper(cfg,armed);UsCalendar calendar=new UsCalendar(cfg.calendar);LocalDate day=LocalDate.now(UsCalendar.ZONE);
        if(!calendar.permits(day))throw new IllegalArgumentException("No permitted US session today; calendar coverage required");
        Path root=accountRoot(cfg,profile),dir=root.resolve(day.toString()),stop=root.resolve("STOP");
        Files.createDirectories(dir);boolean recovery=Files.exists(dir.resolve("engine.journal"));
        if(!recovery&&!Instant.now().isBefore(calendar.open(day).toInstant()))throw new IllegalArgumentException("Start before 09:30 New York; late starts only recover existing journals");
        if(!Instant.now().isBefore(calendar.close(day).toInstant()))throw new IllegalArgumentException("Market closed: inspect and manage outstanding orders in TWS");
        if(Files.exists(stop)&&!recovery)throw new IllegalArgumentException("STOP exists; inspect prior flatness before deleting it and starting a new session");
        AtomicBoolean stopping=new AtomicBoolean();CountDownLatch ended=new CountDownLatch(1);
        Thread hook=new Thread(()->{stopping.set(true);try{ended.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}},"ibkr-paper-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        String context=context(cfg,profile);
        try(Journal accountLock=new Journal(root.resolve("account.lock"));Journal journal=new Journal(dir.resolve("engine.journal"));
            IbkrPaperBroker broker=new IbkrPaperBroker(cfg,dir.resolve("broker.journal"),context,armed)){
            Map<String,Equity> assets=broker.qualify(profile.risk.path("instruments"),profile.risk.symbols,day);
            StringBuilder listings=new StringBuilder("instrument,conid,tick,currency\n");assets.values().forEach(e->listings.append(e.id).append(',').append(e.token).append(',').append(e.tick).append(",USD\n"));
            Files.writeString(dir.resolve("qualified-contracts.csv"),listings.toString());
            Path raw=dir.resolve("five-second-bars.csv");if(!Files.exists(raw))Files.writeString(raw,"instrument,epoch,open,high,low,close,raw_volume,received_utc\n");
            TradingEngine engine=new TradingEngine(profile.risk,day,assets,broker,journal,UsPaperProfile.COSTS,context,calendar.policy(day));
            Map<String,MinuteBars> aggregators=new HashMap<>();assets.keySet().forEach(id->aggregators.put(id,new MinuteBars()));
            broker.subscribe();
            System.out.println("IBKR PAPER ONLY; USD budget "+profile.risk.capital+". Reports: "+dir);
            System.out.println("ORB 09:30-09:45 ET; last entries before 10:15 ET; flatten 10:30 ET. Stop file: "+stop);
            Instant nextPoll=Instant.MIN,nextReport=Instant.MIN;String connection="IBKR_PAPER";
            try{
                while(true){
                    Instant now=Instant.now();
                    if(stopping.get()||Files.exists(stop))engine.flatten("Operator stop");
                    if(!broker.dataFailure().isEmpty())engine.flatten(broker.dataFailure());
                    for(String id:assets.keySet()){var q=broker.quote(id);if(q!=null&&q.price()>0)engine.mark(id,q.price(),q.bid(),q.ask(),q.at());}
                    if(!now.isBefore(nextPoll)){
                        engine.poll(now);nextPoll=Instant.now().plusSeconds(1);
                        if(engine.halted()&&engine.flat())engine.flatten(engine.reason());
                    }
                    IbkrPaperBroker.Bar b;
                    while((b=broker.nextBar())!=null){
                        Files.writeString(raw,b.instrument()+","+b.epoch()+","+b.open()+","+b.high()+","+b.low()+","+b.close()+","+b.volume()+","+b.received()+"\n",StandardOpenOption.APPEND);
                        Optional<Candle> candle=aggregators.get(b.instrument()).accept(b.instrument(),b.epoch(),b.open(),b.high(),b.low(),b.close(),b.volume());
                        if(candle.isPresent()){
                            Instant observed=Instant.now();
                            // Do not send a queued old observation backwards through the portfolio clock.
                            if(Duration.between(candle.get().getTimestamp().toInstant().plusSeconds(60),observed).abs().getSeconds()<=profile.risk.maxSignalDelaySeconds)
                                engine.closed(candle.get(),observed);
                        }
                    }
                    if(!Instant.now().isBefore(nextReport)||engine.finished()){
                        UsPaperReports.write(dir,engine,broker.executionLines(),connection);nextReport=Instant.now().plusSeconds(5);
                    }
                    if(engine.finished())break;
                    if(!Instant.now().isBefore(calendar.close(day).toInstant()))throw new IllegalStateException("Market closed without confirmed flatness; reconcile in TWS");
                    Thread.sleep(100);
                }
            }catch(Exception e){
                engine.flatten("Runtime fault: "+e.getClass().getSimpleName()+"; inspect TWS");
                // A healthy socket may still manage protective exits after a data/runtime fault.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                try{do{engine.poll(Instant.now());if(engine.finished())break;Thread.sleep(250);}while(System.nanoTime()<deadline);}catch(Exception ignored){connection="DISCONNECTED_OR_UNCONFIRMED";}
                UsPaperReports.write(dir,engine,broker.executionLines(),connection);
                throw new IllegalStateException("Paper runtime stopped. Check status.txt and TWS orders/positions; shutdown is not proof of flatness",e);
            }
            System.out.println("Finished with broker-confirmed flat positions and terminal orders. See "+dir.resolve("status.txt"));
        }finally{ended.countDown();try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}}
    }
}
