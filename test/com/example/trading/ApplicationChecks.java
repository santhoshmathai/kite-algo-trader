package com.example.trading;

import com.example.trading.app.*;
import com.example.trading.broker.*;
import com.example.trading.core.Candle;
import com.example.trading.replay.Backtest;
import com.example.trading.storage.Journal;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Executable failure-path and replay contract checks; no credentials or network calls. */
public final class ApplicationChecks {
    private static int passed;
    private static final LocalDate DAY=LocalDate.of(2026,9,7);
    private static final Instant OPEN=DAY.atTime(9,15).atZone(SessionCalendar.ZONE).toInstant();
    private interface Check {void run()throws Exception;}
    private static void check(String name,Check c)throws Exception{c.run();passed++;System.out.println("PASS "+name);}
    private static void yes(boolean value){if(!value)throw new AssertionError("Expected true");}
    private static void equal(double a,double b){if(Math.abs(a-b)>1e-7)throw new AssertionError(a+" != "+b);}
    private static void fails(Check c)throws Exception{try{c.run();}catch(Exception e){return;}throw new AssertionError("Expected failure");}
    private static Settings settings(String...overrides)throws Exception{
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(Path.of("examples/synthetic.properties"))){p.load(r);}
        for(String key:List.of("instruments","calendar","fees","state","sessionFile"))p.setProperty(key,Path.of("examples").resolve(p.getProperty(key)).toAbsolutePath().normalize().toString().replace('\\','/'));
        for(int i=0;i<overrides.length;i+=2)p.setProperty(overrides[i],overrides[i+1]);
        Path dir=Files.createTempDirectory(Path.of("build"),"app-check-");Path file=dir.resolve("settings.properties");try(Writer w=Files.newBufferedWriter(file)){p.store(w,"test");}return new Settings(file);
    }
    private static Candle candle(int minute,double open,double high,double low,double close,long volume){return new Candle(OPEN.plusSeconds(minute*60L).atZone(SessionCalendar.ZONE),"NSE:DEMO",open,high,low,close,volume);}
    private static final class Rig implements AutoCloseable{
        final Settings cfg;final Map<String,Equity> assets;final Fees fees;final SimBroker sim;final Broker broker;final Path path;Journal journal;TradingEngine engine;
        Rig(Settings cfg,Broker supplied)throws Exception{this.cfg=cfg;assets=Equity.read(cfg.path("instruments"),cfg.symbols);fees=new Fees(cfg.path("fees"));sim=supplied==null?new SimBroker(cfg.capital,cfg.slippageBps,cfg.maxParticipation):null;broker=supplied==null?sim:supplied;path=cfg.base.resolve("events.journal");journal=new Journal(path);engine=new TradingEngine(cfg,DAY,assets,broker,journal,fees,"test-context");}
        void signal()throws Exception{
            for(int m=0;m<16;m++){Candle c=m==15?candle(m,100,102,100,102,10000):candle(m,100,101,99,100,10000);Instant t=c.getTimestamp().toInstant().plusSeconds(60);if(sim!=null)sim.time(t.toEpochMilli());engine.mark("NSE:DEMO",c.getClose(),c.getClose(),c.getClose(),t);engine.poll(t);engine.closed(c,t);engine.poll(t);}
        }
        void open(int minute,double price,long volume)throws Exception{Candle c=candle(minute,price,price,price,price,volume);Instant t=c.getTimestamp().toInstant();engine.mark("NSE:DEMO",price,price,price,t);sim.open(c);engine.poll(t);engine.poll(t);}
        TradingEngine.Trade trade(){return engine.trades().get(0);}
        public void close()throws Exception{journal.close();}
    }
    private static class WrappedBroker implements Broker {
        final SimBroker delegate=new SimBroker(10000,0,.01);int submissions;
        public String submit(Request r)throws Exception{submissions++;return delegate.submit(r);}
        public void cancel(String id)throws Exception{delegate.cancel(id);}
        public void modify(String id,Request r)throws Exception{delegate.modify(id,r);}
        public Snapshot snapshot()throws Exception{return delegate.snapshot();}
    }
    public static void main(String[] args)throws Exception{runAll();}
    public static void runAll()throws Exception{
        Files.createDirectories(Path.of("build"));
        check("strict settings and live defaults",()->{Settings c=settings();yes(!c.liveEnabled&&!c.shorts);fails(()->settings("maxPositions","2.5"));fails(()->settings("mystery","1"));fails(()->settings("maxSignalDelaySeconds","8"));});
        check("live gate executes before session/network access",()->fails(()->TradingRuntime.run(settings(),true,true)));
        check("calendar excludes holiday weekend and unknown year",()->{SessionCalendar c=new SessionCalendar(settings().path("calendar"));yes(c.isOpenDate(DAY));yes(!c.isOpenDate(LocalDate.of(2026,9,14)));yes(!c.isOpenDate(LocalDate.of(2026,9,6)));yes(!c.isOpenDate(LocalDate.of(2027,1,4)));});
        check("dated fees and order brokerage cap",()->{Fees f=new Fees(settings().path("fees"));equal(f.estimate(DAY,"BUY",100000),(20+3.07+.1+.0001)*1.18+3);equal(f.estimate(DAY,"SELL",100000),(20+3.07+.1+.0001)*1.18+25);fails(()->f.estimate(DAY.minusDays(1),"BUY",100));});
        check("tick size rounds in the requested direction",()->{Equity e=new Equity("DEMO",1,.05,"",DAY);equal(e.price(100.021,true),100.05);equal(e.price(100.021,false),100);});
        check("journal lock and restart persist acknowledged state",()->{Path p=settings().base.resolve("lock.journal");try(Journal j=new Journal(p)){Properties x=new Properties();x.setProperty("sample","42");j.save(x);fails(()->{try(Journal other=new Journal(p)){} });}try(Journal j=new Journal(p)){yes(j.latest().getProperty("sample").equals("42"));}});
        check("journal truncates incomplete tail but rejects checksum corruption",()->{Path p=settings().base.resolve("tail.journal");try(Journal j=new Journal(p)){j.save(new Properties());}long size=Files.size(p);Files.write(p,new byte[]{1,2,3},StandardOpenOption.APPEND);try(Journal j=new Journal(p)){equal(Files.size(p),size);}byte[] b=Files.readAllBytes(p);b[b.length-1]^=1;Files.write(p,b);fails(()->{try(Journal j=new Journal(p)){} });});
        check("cumulative live bars handle zero-volume first snapshots",()->{LiveBars b=new LiveBars(DAY,new SessionCalendar(settings().path("calendar")));b.quote(new KiteBroker.Quote("NSE:DEMO",OPEN,100,100,100,0));b.quote(new KiteBroker.Quote("NSE:DEMO",OPEN.plusSeconds(1),101,101,101,10));yes(b.closeThrough(OPEN.plusSeconds(59)).isEmpty());List<Candle> cs=b.closeThrough(OPEN.plusSeconds(60));yes(cs.size()==1);equal(cs.get(0).getVolume(),10);fails(()->b.quote(new KiteBroker.Quote("NSE:DEMO",OPEN.plusSeconds(2),102,102,102,20)));});
        check("live bars reject gaps decreasing volume and late startup",()->{LiveBars b=new LiveBars(DAY,new SessionCalendar(settings().path("calendar")));fails(()->b.quote(new KiteBroker.Quote("NSE:DEMO",OPEN.plusSeconds(60),100,100,100,10)));LiveBars a=new LiveBars(DAY,new SessionCalendar(settings().path("calendar")));a.quote(new KiteBroker.Quote("NSE:DEMO",OPEN,100,100,100,10));fails(()->a.quote(new KiteBroker.Quote("NSE:DEMO",OPEN.plusSeconds(1),100,100,100,9)));fails(()->a.quote(new KiteBroker.Quote("NSE:DEMO",OPEN.plusSeconds(120),100,100,100,20)));});
        check("entry is unfilled on signal and quantity includes estimated costs",()->{try(Rig r=new Rig(settings(),null)){r.signal();TradingEngine.Tracked e=r.engine.tracked(r.trade().entryTag);yes(e.filled==0&&e.request.quantity==8);yes(!r.engine.flat());}});
        check("partial entry cancels remainder and protects only filled quantity",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,100);TradingEngine.Tracked e=r.engine.tracked(r.trade().entryTag);yes(e.filled==1&&e.status.equals("CANCELLED"));yes(r.trade().exits.size()==1);TradingEngine.Tracked stop=r.engine.tracked(r.trade().exits.get(0));yes(stop.request.quantity==1&&stop.request.type.equals("SL-M"));}});
        check("entry bar stop can fill before next candle",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,10000);r.sim.range(candle(16,102,108,98,107,10000));r.engine.poll(OPEN.plusSeconds(16*60));yes(r.engine.flat());equal(r.engine.tradeGross(r.trade()),-24);}});
        check("target converts existing protection and fills next observation",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,10000);String stop=r.trade().exits.get(0);Instant at=OPEN.plusSeconds(17*60);r.sim.time(at.toEpochMilli());r.engine.mark("NSE:DEMO",107,107,107,at);r.engine.poll(at);r.engine.poll(at);yes(r.trade().exits.size()==1&&r.engine.tracked(stop).request.type.equals("MARKET"));r.open(17,106,10000);yes(r.engine.flat());equal(r.engine.tradeGross(r.trade()),32);}});
        check("unfilled limit expires without a position",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.engine.poll(OPEN.plusSeconds(16*60+15));r.engine.poll(OPEN.plusSeconds(16*60+15));yes(r.engine.flat());yes(r.engine.tracked(r.trade().entryTag).filled==0);}});
        check("stale prices trigger exit and halt new entries",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,10000);r.engine.poll(OPEN.plusSeconds(16*60+16));yes(r.engine.halted());yes(r.engine.tracked(r.trade().exits.get(0)).request.type.equals("MARKET"));}});
        check("morning cutoff converts stop and waits for actual fill",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,10000);Instant at=OPEN.plusSeconds(60*60);r.sim.time(at.toEpochMilli());r.engine.mark("NSE:DEMO",102,102,102,at);r.engine.poll(at);r.engine.poll(at);yes(!r.engine.finished());r.open(60,102,10000);yes(r.engine.finished());}});
        check("daily loss uses marked PnL and forces liquidation",()->{try(Rig r=new Rig(settings("dailyLoss","25","maxOpenRisk","25"),null)){r.signal();r.open(16,102,10000);Instant at=OPEN.plusSeconds(16*60+1);r.engine.mark("NSE:DEMO",90,90,90,at);r.engine.poll(at);yes(r.engine.halted()&&r.engine.reason().equals("Daily loss limit"));}});
        check("restart reconciles and flattens without repeating an entry",()->{try(Rig r=new Rig(settings(),null)){r.signal();r.open(16,102,10000);r.journal.close();r.journal=new Journal(r.path);r.engine=new TradingEngine(r.cfg,DAY,r.assets,r.broker,r.journal,r.fees,"test-context");yes(r.engine.halted()&&!r.engine.flat());Instant at=OPEN.plusSeconds(17*60);r.sim.time(at.toEpochMilli());r.engine.mark("NSE:DEMO",102,102,102,at);r.engine.poll(at);r.engine.poll(at);r.open(17,102,10000);yes(r.engine.finished());yes(r.engine.trades().size()==1);}});
        check("restart refuses changed context",()->{try(Rig r=new Rig(settings(),null)){fails(()->new TradingEngine(r.cfg,DAY,r.assets,r.broker,r.journal,r.fees,"different"));}});
        check("uncertain submission is never blindly repeated",()->{final int[] submits={0};Broker b=new Broker(){public String submit(Request r)throws Exception{submits[0]++;throw new IOException("timeout");}public void cancel(String i){}public void modify(String i,Request r){}public Snapshot snapshot(){return new Snapshot(List.of(),Map.of(),10000);}};try(Rig r=new Rig(settings(),b)){r.signal();r.engine.poll(OPEN.plusSeconds(17*60));r.engine.flatten("test");r.engine.poll(OPEN.plusSeconds(18*60));yes(submits[0]==1&&r.engine.halted()&&!r.engine.finished());}});
        check("foreign account exposure cannot be reported flat",()->{Broker b=new Broker(){public String submit(Request r){throw new AssertionError();}public void cancel(String i){}public void modify(String i,Request r){}public Snapshot snapshot(){return new Snapshot(List.of(),Map.of("NSE:OTHER",1),10000);}};try(Rig r=new Rig(settings(),b)){r.engine.flatten("operator");r.engine.poll(OPEN);yes(r.engine.halted()&&!r.engine.flat()&&!r.engine.finished());}});
        check("chronological replay rejects duplicated bars",()->{Settings c=settings();Path p=c.base.resolve("bad.csv");List<String> lines=Files.readAllLines(Path.of("examples/synthetic-bars.csv"));Files.write(p,List.of(lines.get(0),lines.get(1),lines.get(1)));fails(()->Backtest.read(p,new SessionCalendar(c.path("calendar")),Equity.read(c.path("instruments"),c.symbols)));});
        check("synthetic replay ends flat and charges both order sides",()->{Settings c=settings();Path run=Backtest.run(c,Path.of("examples/synthetic-bars.csv"),c.base);List<String> days=Files.readAllLines(run.resolve("days.csv"));yes(days.size()==3&&days.get(1).contains(",true,FINISHED")&&days.get(2).contains(",true,FINISHED"));yes(Files.readString(run.resolve("2026-09-08/trades.csv")).contains("-24.0"));});
        check("truncated replay cannot invent an exit fill",()->{Settings c=settings();Path p=c.base.resolve("short.csv");List<String> lines=Files.readAllLines(Path.of("examples/synthetic-bars.csv"));Files.write(p,lines.subList(0,18));Path run=Backtest.run(c,p,c.base);yes(Files.readString(run.resolve("days.csv")).contains(",false,"));});
        check("accepted-but-timed-out submit is found by tag without duplication",()->{
            WrappedBroker b=new WrappedBroker(){public String submit(Request r)throws Exception{super.submit(r);throw new IOException("lost acknowledgement");}};
            try(Rig r=new Rig(settings(),b)){r.signal();yes(b.submissions==1);yes(r.engine.tracked(r.trade().entryTag).pending.isEmpty());r.engine.poll(OPEN.plusSeconds(17*60));r.engine.poll(OPEN.plusSeconds(17*60));yes(r.engine.flat()&&b.submissions==1);}
        });
        check("exit rejection halts without an order retry storm",()->{
            WrappedBroker b=new WrappedBroker(){Request rejected;
                public String submit(Request r)throws Exception{if(r.type.equals("SL-M")){submissions++;rejected=r;return "reject1";}return super.submit(r);}
                public Snapshot snapshot()throws Exception{Snapshot s=super.snapshot();List<Order> os=new ArrayList<>(s.orders);if(rejected!=null){Request r=rejected;os.add(new Order("reject1",r.tag,r.instrument,r.side,r.type,"REJECTED",r.quantity,0,0,r.price,r.trigger));}return new Snapshot(os,s.positions,s.availableCash);}
            };
            try(Rig r=new Rig(settings(),b)){r.signal();b.delegate.open(candle(16,102,102,102,102,10000));for(int n=0;n<5;n++)r.engine.poll(OPEN.plusSeconds(16*60));yes(b.submissions==2&&r.engine.halted()&&!r.engine.flat());yes(r.engine.reason().contains("Exit rejected"));}
        });
        check("broker order tampering prevents additional requests",()->{
            WrappedBroker b=new WrappedBroker(){boolean tamper;
                public Snapshot snapshot()throws Exception{Snapshot s=super.snapshot();if(submissions==0)return s;List<Order> os=new ArrayList<>();for(Order o:s.orders)os.add(new Order(o.id,o.tag,o.instrument,o.side,o.type,o.status,o.quantity+1,o.filled,o.average,o.price,o.trigger));return new Snapshot(os,s.positions,s.availableCash);}
            };
            try(Rig r=new Rig(settings(),b)){r.signal();yes(r.engine.halted()&&r.engine.reason().contains("modification"));yes(b.submissions==1);}
        });
        check("multiple simultaneous symbols respect portfolio position cap",()->{
            Settings base=settings();Path listings=base.base.resolve("two.csv");Files.writeString(listings,"symbol,token,tick_size,sector,as_of\nAAA,1,0.05,TEST,2026-09-07\nBBB,2,0.05,TEST,2026-09-07\n");
            Settings cfg=settings("symbols","AAA,BBB","instruments",listings.toAbsolutePath().toString().replace('\\','/'),"maxPositions","1");
            try(Rig r=new Rig(cfg,null)){for(int m=0;m<16;m++){Instant t=OPEN.plusSeconds((m+1)*60);r.sim.time(t.toEpochMilli());r.engine.poll(t);for(String id:r.assets.keySet()){Candle c=new Candle(OPEN.plusSeconds(m*60).atZone(SessionCalendar.ZONE),id,100,m==15?102:101,m==15?100:99,m==15?102:100,10000);r.engine.mark(id,c.getClose(),c.getClose(),c.getClose(),t);r.engine.closed(c,t);}}yes(r.engine.trades().size()==1);}
        });
        System.out.println("Application checks passed: "+passed);
    }
}
