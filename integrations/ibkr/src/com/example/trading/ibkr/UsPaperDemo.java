package com.example.trading.ibkr;

import com.example.trading.app.*;
import com.example.trading.broker.*;
import com.example.trading.core.*;
import com.example.trading.storage.Journal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Deterministic plumbing exercise, deliberately not a historical strategy backtest. No socket/API calls. */
public final class UsPaperDemo {
    public static void run(UsPaperProfile profile,Path output)throws Exception{
        if(Files.exists(output.resolve("engine.journal")))throw new IllegalArgumentException("Choose a new demo output directory");
        LocalDate day=LocalDate.of(2026,9,8);String id="IBKR:DEMO";
        Equity asset=new Equity("IBKR","DEMO",1,.01,"DEMO",day);
        SimBroker sim=new SimBroker(profile.risk.capital,0,1);
        Broker broker=new Broker(){
            public String submit(Request r){return sim.submit(r);}public void cancel(String id){sim.cancel(id);}
            public void modify(String id,Request r){sim.modify(id,r);}public Snapshot snapshot(){return sim.snapshot();}
            public boolean cancelBeforeExitTypeChange(){return true;}
        };
        try(Journal journal=new Journal(output.resolve("engine.journal"))){
            TradingSession session=new TradingSession(Set.of(),UsCalendar.ZONE,LocalTime.of(9,30),LocalTime.of(16,0));
            TradingEngine engine=new TradingEngine(profile.risk,day,Map.of(id,asset),broker,journal,UsPaperProfile.COSTS,profile.risk.fingerprint(),session);
            ZonedDateTime start=day.atTime(9,30).atZone(UsCalendar.ZONE);
            for(int n=0;n<16;n++){
                double close=n==15?102:100;Candle c=new Candle(start.plusMinutes(n),id,100,n==15?102:101,99,close,100000,UsCalendar.ZONE);
                Instant at=start.plusMinutes(n+1).toInstant();sim.time(at.toEpochMilli());engine.mark(id,close,close-.01,close+.01,at);engine.poll(at);engine.closed(c,at);
            }
            if(engine.trades().size()!=1)throw new AssertionError("Demo entry not created");
            Instant at=start.plusMinutes(16).plusSeconds(1).toInstant();sim.tick(id,at,102,100000);engine.mark(id,102,101.99,102.01,at);engine.poll(at);
            at=at.plusSeconds(1);engine.poll(at); // Confirm protective stop before requesting target conversion.
            at=at.plusSeconds(1);engine.mark(id,108,107.99,108.01,at);engine.poll(at); // Cancel stop.
            at=at.plusSeconds(1);engine.poll(at); // Confirm cancellation, submit one remaining-quantity market exit.
            at=at.plusSeconds(1);sim.tick(id,at,108,100000);engine.mark(id,108,107.99,108.01,at);engine.poll(at);
            engine.flatten("Demo complete");engine.poll(at.plusSeconds(1));
            if(!engine.finished())throw new AssertionError("Demo failed to flatten");
            UsPaperReports.write(output,engine,List.of(),"OFFLINE_DEMO");
            System.out.println("Offline buy/stop/target-sell cycle verified. Artificial prices; not profitability evidence. Reports: "+output.toAbsolutePath());
        }
    }
}
