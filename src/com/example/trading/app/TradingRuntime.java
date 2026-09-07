package com.example.trading.app;

import com.example.trading.broker.*;
import com.example.trading.core.Candle;
import com.example.trading.replay.Backtest;
import com.example.trading.storage.Journal;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One account/session process with bounded feed queue, periodic reconciliation and a file kill switch. */
public final class TradingRuntime {
    public static void run(Settings cfg,boolean live,boolean armed)throws Exception{
        if(live&&(!cfg.liveEnabled||!armed))throw new IllegalArgumentException("Live requires liveEnabled=true and --arm-live");
        LocalDate date=LocalDate.now(SessionCalendar.ZONE);SessionCalendar calendar=new SessionCalendar(cfg.path("calendar"));
        if(!calendar.isOpenDate(date))throw new IllegalArgumentException("Today is not in the approved calendar");
        Map<String,Equity> assets=Equity.read(cfg.path("instruments"),cfg.symbols);
        for(Equity e:assets.values())if(!e.asOf.equals(date))throw new IllegalArgumentException("Download today's instrument mappings first");
        Fees fees=new Fees(cfg.path("fees"));fees.estimate(date,"BUY",0);
        String hash=Backtest.hash(cfg.file,cfg.path("instruments"),cfg.path("calendar"),cfg.path("fees"));
        try(KiteBroker kite=new KiteBroker(cfg,assets,live)){
            Path accountDir=cfg.path("state").resolve(live?"live":"paper").resolve(kite.account);Files.createDirectories(accountDir);
            try(FileChannel lockChannel=FileChannel.open(accountDir.resolve("account.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=lockChannel.tryLock()){
                if(lock==null)throw new IllegalStateException("Account is already running");
                Path output=accountDir.resolve(date.toString());Files.createDirectories(output);
                if(!live&&Files.exists(output.resolve("events.journal")))throw new IllegalStateException("Paper session already exists; preserve it and use a separate state root for a new simulation");
                Broker broker=live?kite:new SimBroker(cfg.capital,cfg.slippageBps,cfg.maxParticipation);
                try(Journal journal=new Journal(output.resolve("events.journal"))){
                    TradingEngine engine=new TradingEngine(cfg,date,assets,broker,journal,fees,hash);
                    AtomicBoolean shutdown=new AtomicBoolean();CountDownLatch stopped=new CountDownLatch(1);
                    Thread hook=new Thread(()->{shutdown.set(true);try{if(!stopped.await(20,TimeUnit.SECONDS))System.err.println(live?"Shutdown before confirmed flat: inspect Kite orders and positions now.":"Paper shutdown before confirmed flat: retain this session as incomplete; no real orders were submitted.");}catch(InterruptedException e){Thread.currentThread().interrupt();}},"shutdown-request");
                    Runtime.getRuntime().addShutdownHook(hook);
                    try{
                        if(!Instant.now().isBefore(calendar.open(date)))engine.halt("Started after open: manage existing orders only; no entries today");
                        ArrayBlockingQueue<KiteBroker.Quote> queue=new ArrayBlockingQueue<>(20000);AtomicReference<String> feedProblem=new AtomicReference<>("");AtomicBoolean connected=new AtomicBoolean();
                        kite.stream(q->{if(!queue.offer(q))feedProblem.set("Market-data queue overflow");},health->{if(health.equals("CONNECTED")){connected.set(true);if(!Instant.now().isBefore(calendar.open(date)))feedProblem.set("Feed connected/reconnected after open");}else{connected.set(false);feedProblem.set(health);}});
                        LiveBars builder=new LiveBars(date,calendar);Map<String,Long> volumes=new HashMap<>();long pollAt=0,reportAt=0;
                        boolean dataFailed=false;
                        System.out.println((live?"LIVE":"PAPER")+" session. Output: "+output+". Create "+accountDir.resolve("STOP")+" to halt entries and flatten.");
                        while(true){
                            if(shutdown.get()||Files.exists(accountDir.resolve("STOP")))engine.flatten("Operator stop requested");
                            String fault=feedProblem.getAndSet("");if(!fault.isEmpty()){dataFailed=true;engine.flatten("Feed fault: "+fault);}
                            KiteBroker.Quote quote;int drained=0;
                            while(drained++<20000&&(quote=queue.poll())!=null){
                                if(quote.time.isAfter(Instant.now().plusSeconds(2))){engine.flatten("Future market timestamp");dataFailed=true;continue;}
                                engine.mark(quote.instrument,quote.price,quote.bid,quote.ask,quote.time);
                                if(broker instanceof SimBroker){Long prior=volumes.put(quote.instrument,quote.volume);long delta=prior==null?0:Math.max(0,quote.volume-prior);((SimBroker)broker).tick(quote.instrument,quote.time,quote.price,delta);}
                                if(!dataFailed)try{builder.quote(quote);}catch(IllegalArgumentException e){dataFailed=true;engine.flatten(e.getMessage());}
                            }
                            Instant now=Instant.now();
                            if(now.toEpochMilli()>=pollAt){
                                try{if(broker instanceof SimBroker)((SimBroker)broker).time(now.toEpochMilli());engine.poll(now);}
                                catch(Exception e){engine.halt("Broker reconciliation unavailable: "+e.getClass().getSimpleName());System.err.println(live?"Reconciliation failed; entries halted. Check connection and Kite protective orders.":"Paper reconciliation failed; entries halted. Preserve the session journal for investigation.");}
                                pollAt=System.currentTimeMillis()+2000;
                            }
                            if(!dataFailed){for(Candle bar:builder.closeThrough(Instant.now().minusSeconds(2))){if(broker instanceof SimBroker)((SimBroker)broker).time(Instant.now().toEpochMilli());engine.closed(bar,Instant.now());}}
                            if(System.currentTimeMillis()>=reportAt){Reports.write(output,engine,live?"LIVE":"PAPER",hash);System.out.printf(Locale.ROOT,"%s %s net INR %.2f; %s%n",LocalTime.now(SessionCalendar.ZONE),engine.modeStatus(),engine.netPnl(),engine.reason());reportAt=System.currentTimeMillis()+30000;}
                            if(engine.finished()){Reports.write(output,engine,live?"LIVE":"PAPER",hash);break;}
                            if(!now.atZone(SessionCalendar.ZONE).toLocalDate().equals(date))throw new IllegalStateException("Session passed midnight unresolved; inspect broker manually");
                            Thread.sleep(100);
                        }
                    }finally{stopped.countDown();try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException ignored){}}
                }
            }
        }
    }
}
