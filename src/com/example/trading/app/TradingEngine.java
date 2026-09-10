package com.example.trading.app;

import com.example.trading.broker.Broker;
import com.example.trading.core.*;
import com.example.trading.storage.Journal;
import com.example.trading.strategy.ORBStrategy;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Single-owner morning coordinator; strategies, portfolio limits and broker order state share one lifecycle. */
public final class TradingEngine {
    public static final class Tracked {
        public Broker.Request request;
        public String brokerId="",status="INTENT",pending="SUBMIT";
        public int filled;
        public double average;
        Tracked(Broker.Request r){request=r;}
        public boolean terminal(){return Set.of("COMPLETE","CANCELLED","REJECTED").contains(status);}
    }
    public static final class Trade {
        public String instrument,side,entryTag,reason="";
        public Instant created;
        public double stop;
        public final List<String> exits=new ArrayList<>();
    }
    private static final class Mark { double price,bid,ask;Instant at;Mark(double p,double b,double a,Instant t){price=p;bid=b;ask=a;at=t;} }
    public final Settings config;
    public final LocalDate date;
    private final Map<String,Equity> assets;
    private final Broker broker;
    private final Journal journal;
    private final TradeCosts fees;
    private final TradingSession session;
    private final Map<String,ORBStrategy> strategies=new TreeMap<>();
    private final Map<String,Mark> marks=new HashMap<>();
    private final Map<String,Tracked> orders=new LinkedHashMap<>();
    private final Map<String,Trade> trades=new LinkedHashMap<>();
    private final Set<String> attempted=new HashSet<>();
    private final List<String> messages=new ArrayList<>();
    private boolean halted, forceExit, reconciled;
    private String reason="",runId;
    private Instant now;
    private double brokerCash;
    private final String contextHash;

    public TradingEngine(Settings config,LocalDate date,Map<String,Equity> assets,Broker broker,Journal journal,Fees fees,String contextHash)throws IOException{
        this(config,date,assets,broker,journal,fees,contextHash,new TradingSession());
    }
    public TradingEngine(Settings config,LocalDate date,Map<String,Equity> assets,Broker broker,Journal journal,TradeCosts fees,String contextHash,TradingSession session)throws IOException{
        this.session=session;this.config=config;this.date=date;this.assets=Map.copyOf(assets);this.broker=broker;this.journal=journal;this.fees=fees;this.contextHash=contextHash;
        now=date.atStartOfDay(session.zone()).toInstant();brokerCash=config.capital;
        for(Equity e:assets.values())strategies.put(e.id,new ORBStrategy(e.strategyInstrument(),session,15,config.cutoff,e.tick.doubleValue()*config.bufferTicks,config.shorts));
        Properties previous=journal.latest();
        if(previous.containsKey("date")){
            if(!date.toString().equals(previous.getProperty("date")))throw new IOException("Journal belongs to another session. Archive only after broker flatness is verified.");
            if(!contextHash.equals(previous.getProperty("context")))throw new IOException("Configuration/data context changed; use original settings for recovery");
            restore(previous);halted=true;forceExit=true;reason="Restart: manage and flatten existing orders only";
        }else{runId="K"+UUID.randomUUID().toString().replace("-","").substring(0,14);save();}
    }
    public void mark(String instrument,double price,double bid,double ask,Instant at){
        if(!assets.containsKey(instrument)||!Double.isFinite(price)||price<=0)throw new IllegalArgumentException("Invalid quote");
        Mark old=marks.get(instrument);if(old==null||!at.isBefore(old.at))marks.put(instrument,new Mark(price,bid,ask,at));
    }
    public void closed(Candle candle,Instant observed)throws Exception{
        time(observed);String key=candle.getInstrumentToken();ORBStrategy strategy=strategies.get(key);if(strategy==null)return;
        if(Duration.between(candle.getTimestamp().toInstant().plusSeconds(60),observed).toMillis()>config.maxSignalDelaySeconds*1000L)return;
        Optional<ORBStrategy.Signal> signal=strategy.onClosedCandle(candle,observed.atZone(session.zone()));
        if(signal.isEmpty()||attempted.contains(key))return;
        attempted.add(key);save();
        if(halted||forceExit||!now.atZone(session.zone()).toLocalTime().isBefore(config.cutoff)||trades.size()>=config.maxTrades){note("SKIP "+key+" entry limits");return;}
        long active=trades.values().stream().filter(this::active).count();if(active>=config.maxPositions){note("SKIP "+key+" position cap");return;}
        Mark mark=marks.get(key);if(mark==null||Duration.between(mark.at,now).getSeconds()>config.staleSeconds||mark.at.isAfter(now)) {note("SKIP "+key+" stale quote");return;}
        if(!Double.isFinite(mark.bid)||!Double.isFinite(mark.ask)||mark.bid<=0||mark.ask<mark.bid||(mark.ask-mark.bid)/mark.price*10000>config.maxSpreadBps){note("SKIP "+key+" spread unavailable/wide");return;}
        Equity e=assets.get(key);boolean buy=signal.get().side.equals("BUY");
        double limit=e.price(signal.get().referencePrice*(1+(buy?1:-1)*config.slippageBps/10000),buy);
        double stop=e.price(signal.get().stopPrice,!buy),distance=buy?limit-stop:stop-limit;
        if(distance/limit<config.minStopFraction||distance/limit>config.maxStopFraction){note("SKIP "+key+" stop distance");return;}
        double costs=fees.estimate(date,buy?"BUY":"SELL",1,limit)+fees.estimate(date,buy?"SELL":"BUY",1,stop);
        double lossPerShare=distance+costs+stop*config.slippageBps/10000;
        double cash=Math.max(0,Math.min(brokerCash,config.capital+netPnl())-reservedNotional());
        double riskBudget=Math.max(0,Math.min(config.tradeRisk,config.maxOpenRisk-reservedRisk()));
        int quantity=(int)Math.min(Integer.MAX_VALUE,Math.floor(Math.min(Math.min(riskBudget/lossPerShare,cash/(limit+costs)),
                Math.min(config.maxNotional/limit,candle.getVolume()*config.maxParticipation))));
        if(quantity<=0){note("SKIP "+key+" risk/cash/liquidity sizing");return;}
        Trade t=new Trade();t.instrument=key;t.side=signal.get().side;t.stop=stop;t.created=now;t.entryTag=tag();trades.put(key,t);
        create(new Broker.Request(key,t.side,"LIMIT",quantity,limit,0,t.entryTag));
    }
    private String tag(){return runId+Integer.toString(orders.size()+1,36);}
    private void time(Instant at){if(at.isBefore(now))throw new IllegalArgumentException("Engine clock moved backwards");now=at;}
    public void poll(Instant at)throws Exception{
        time(at);reconciled=false;
        if(!date.equals(now.atZone(session.zone()).toLocalDate())) {halt("Session date changed; operator recovery required");return;}
        Broker.Snapshot snapshot=broker.snapshot();
        if(!Double.isFinite(snapshot.availableCash)||snapshot.availableCash<0){halt("Invalid broker cash");return;}brokerCash=snapshot.availableCash;
        Map<String,Broker.Order> byTag=new HashMap<>();
        for(Broker.Order o:snapshot.orders){
            if(orders.containsKey(o.tag)){
                if(byTag.put(o.tag,o)!=null){halt("Duplicate broker tag: reconciliation required");return;}
            }else if(!o.terminal()){halt("Unmanaged broker order; reconcile account at the broker");return;}
        }
        boolean pendingUnknown=false;
        for(Map.Entry<String,Tracked> entry:orders.entrySet()){
            Tracked local=entry.getValue();Broker.Order remote=byTag.get(entry.getKey());
            if(remote==null){if(!local.terminal())pendingUnknown=true;continue;}
            if(!local.request.instrument.equals(remote.instrument)||!local.request.side.equals(remote.side)||remote.filled<0||remote.filled>remote.quantity
                    ||remote.filled<local.filled||!Double.isFinite(remote.average)||(remote.filled>0&&remote.average<=0)) {halt("Broker order mismatch");return;}
            if(remote.filled==local.filled&&local.filled>0&&Math.abs(remote.average-local.average)>1e-6){halt("Fill correction requires reconciliation");return;}
            if(local.terminal()&&!local.status.equals(remote.status)){halt("Terminal order state changed; reconciliation required");return;}
            if(!local.pending.equals("MODIFY")&&(remote.quantity!=local.request.quantity||!remote.type.equals(local.request.type)
                    ||Math.abs(remote.price-local.request.price)>1e-6&&remote.type.equals("LIMIT")
                    ||Math.abs(remote.trigger-local.request.trigger)>1e-6&&remote.type.equals("SL-M"))){halt("Unexpected broker order modification");return;}
            local.brokerId=remote.id;local.status=remote.status;local.filled=remote.filled;local.average=remote.average;
            if(local.pending.equals("SUBMIT")||local.pending.equals("CANCEL")&&remote.terminal()
                    ||local.pending.equals("MODIFY")&&(remote.terminal()||remote.type.equals(local.request.type)&&remote.quantity==local.request.quantity))local.pending="";
            if(!local.pending.isEmpty())pendingUnknown=true;
        }
        save(); // Confirmations reach disk before any further action.
        Map<String,Integer> expected=new HashMap<>();for(Trade t:trades.values()){int n=remaining(t);if(n<0){halt("Over-exit detected; reconcile broker position");return;}if(n>0)expected.put(t.instrument,t.side.equals("BUY")?n:-n);}
        if(!expected.equals(snapshot.positions)){halt("Broker/local positions differ; reconcile before further orders");return;}
        if(pendingUnknown){halted=true;reason="Uncertain broker request; polling for confirmation, no resubmission";save();return;}
        reconciled=true;
        if(!now.atZone(session.zone()).toLocalTime().isBefore(config.flatten)){forceExit=true;reason="Morning time exit";}
        if(netPnl()<=-config.dailyLoss){forceExit=true;halted=true;reason="Daily loss limit";}
        for(Trade t:new ArrayList<>(trades.values()))manage(t);
        save();
    }
    private void manage(Trade t)throws Exception{
        Tracked entry=orders.get(t.entryTag);int remaining=remaining(t);Mark m=marks.get(t.instrument);
        if(!entry.terminal()&&(entry.filled>0||forceExit||Duration.between(t.created,now).getSeconds()>=config.entryTtlSeconds
                ||!now.atZone(session.zone()).toLocalTime().isBefore(config.cutoff)))cancel(entry);
        if(remaining==0){for(String tag:t.exits){Tracked x=orders.get(tag);if(!x.terminal())cancel(x);
            if(t.reason.isEmpty()&&x.filled>0&&x.request.type.equals("SL-M"))t.reason="Broker stop fill";}return;}
        boolean stale=m==null||Duration.between(m.at,now).getSeconds()>config.staleSeconds;
        if(stale){halted=true;t.reason="Stale market data";}
        double entryPrice=entry.average;
        boolean buy=t.side.equals("BUY");double target=entryPrice+(buy?1:-1)*config.reward*Math.abs(entryPrice-t.stop);
        boolean stop=m!=null&&(buy?m.price<=t.stop:m.price>=t.stop),profit=m!=null&&(buy?m.price>=target:m.price<=target);
        if(forceExit||stale||stop||profit){if(t.reason.isEmpty())t.reason=forceExit?reason:stop?"Stop":"Target";}
        Tracked active=null;
        for(String tag:t.exits){Tracked x=orders.get(tag);if(!x.terminal()){if(active!=null){halt("Multiple active exits");return;}active=x;}}
        String side=buy?"SELL":"BUY";
        if(active==null&&!t.exits.isEmpty()&&orders.get(t.exits.get(t.exits.size()-1)).status.equals("REJECTED")){
            halt("Exit rejected: inspect and close position at the broker; automatic retries disabled");return;
        }
        // Convert the same protective order to MARKET for a target/time exit; do not race a second close order.
        if(active==null){String tag=tag();t.exits.add(tag);String type=t.reason.isEmpty()?"SL-M":"MARKET";
            create(new Broker.Request(t.instrument,side,type,remaining,0,t.stop,tag));}
        else if(active.pending.isEmpty()){
            int required=active.filled+remaining;String type=t.reason.isEmpty()?active.request.type:"MARKET";
            if(!active.request.type.equals(type)&&broker.cancelBeforeExitTypeChange())cancel(active);
            else if(active.request.quantity!=required||!active.request.type.equals(type))modify(active,new Broker.Request(t.instrument,side,type,required,0,t.stop,active.request.tag));
        }
    }
    private void create(Broker.Request r)throws Exception{
        Tracked t=new Tracked(r);orders.put(r.tag,t);note("INTENT "+r.tag+" "+r.side+" "+r.quantity+" "+r.instrument+" "+r.type);save();
        try{t.brokerId=broker.submit(r);if(t.brokerId==null||t.brokerId.isBlank())throw new IOException("Missing broker acknowledgement");}
        catch(Exception e){halted=true;reason="Submission uncertain; reconciliation required";note(reason);save();return;}
        // SUBMIT remains pending until snapshot verifies the order.
        save();
    }
    private void cancel(Tracked t)throws Exception{
        if(!t.pending.isEmpty())return;t.pending="CANCEL";note("CANCEL "+t.request.tag);save();
        try{broker.cancel(t.brokerId);}catch(Exception e){halted=true;reason="Cancellation uncertain";}save();
    }
    private void modify(Tracked t,Broker.Request r)throws Exception{
        t.pending="MODIFY";t.request=r;note("MODIFY "+r.tag+" "+r.type+" "+r.quantity);save();try{broker.modify(t.brokerId,r);}catch(Exception e){halted=true;reason="Modification uncertain";}save();
    }
    public void halt(String why)throws IOException{halted=true;reason=why;note(why);save();}
    public void flatten(String why)throws IOException{forceExit=true;halted=true;reason=why;save();}
    private void note(String s){messages.add(now+" "+s);if(messages.size()>1000)messages.remove(0);}
    private boolean active(Trade t){return remaining(t)>0||!orders.get(t.entryTag).terminal();}
    public int remaining(Trade t){int q=orders.get(t.entryTag).filled;for(String tag:t.exits)q-=orders.get(tag).filled;return q;}
    private double reservedNotional(){double n=0;for(Trade t:trades.values()){Tracked e=orders.get(t.entryTag);int q=remaining(t)+(e.terminal()?0:e.request.quantity-e.filled);n+=q*e.request.price;}return n;}
    private double reservedRisk(){double n=0;for(Trade t:trades.values()){Tracked e=orders.get(t.entryTag);int q=remaining(t)+(e.terminal()?0:e.request.quantity-e.filled);n+=q*(Math.abs(e.request.price-t.stop)+fees.estimate(date,t.side,1,e.request.price)+fees.estimate(date,opposite(t.side),1,t.stop)+t.stop*config.slippageBps/10000);}return n;}
    public double tradeGross(Trade t){
        Tracked entry=orders.get(t.entryTag);BigDecimal value=BigDecimal.ZERO;
        for(String tag:t.exits){Tracked x=orders.get(tag);value=value.add(BigDecimal.valueOf(x.average).subtract(BigDecimal.valueOf(entry.average)).multiply(BigDecimal.valueOf(x.filled)));}
        Mark m=marks.get(t.instrument);double mark=m==null?entry.average:m.price;
        value=value.add(BigDecimal.valueOf(mark).subtract(BigDecimal.valueOf(entry.average)).multiply(BigDecimal.valueOf(remaining(t))));
        return value.multiply(BigDecimal.valueOf(t.side.equals("BUY")?1:-1)).doubleValue();
    }
    public double tradeCosts(Trade t){double cost=0;Tracked e=orders.get(t.entryTag);cost+=fees.estimate(date,e.request.side,e.filled,e.average);
        for(String tag:t.exits){Tracked x=orders.get(tag);cost+=fees.estimate(date,x.request.side,x.filled,x.average);}return cost;}
    public double netPnl(){double sum=0;for(Trade t:trades.values())sum+=tradeGross(t)-tradeCosts(t);return sum;}
    public boolean flat(){return reconciled&&trades.values().stream().allMatch(t->remaining(t)==0)&&orders.values().stream().allMatch(t->t.terminal()&&t.pending.isEmpty());}
    public boolean finished(){return forceExit&&flat();}
    public boolean halted(){return halted;}
    public String reason(){return reason;}
    public List<Trade> trades(){return new ArrayList<>(trades.values());}
    public List<String> messages(){return new ArrayList<>(messages);}
    public Tracked tracked(String tag){return orders.get(tag);}
    public String modeStatus(){return finished()?"FINISHED":halted?"HALTED":forceExit?"FLATTENING":"RUNNING";}
    private static String opposite(String side){return side.equals("BUY")?"SELL":"BUY";}
    private void save()throws IOException{
        Properties p=new Properties();p.setProperty("date",date.toString());p.setProperty("context",contextHash);p.setProperty("runId",runId);
        p.setProperty("halted",""+halted);p.setProperty("forceExit",""+forceExit);p.setProperty("reason",reason);p.setProperty("attempted",String.join(",",attempted));
        p.setProperty("messages",String.join("\n",messages));
        p.setProperty("orders",""+orders.size());int n=0;
        for(Tracked o:orders.values()){String k="o."+(n++)+".";Broker.Request r=o.request;
            p.setProperty(k+"instrument",r.instrument);p.setProperty(k+"side",r.side);p.setProperty(k+"type",r.type);p.setProperty(k+"quantity",""+r.quantity);
            p.setProperty(k+"price",""+r.price);p.setProperty(k+"trigger",""+r.trigger);p.setProperty(k+"tag",r.tag);p.setProperty(k+"id",o.brokerId);
            p.setProperty(k+"status",o.status);p.setProperty(k+"pending",o.pending);p.setProperty(k+"filled",""+o.filled);p.setProperty(k+"average",""+o.average);}
        p.setProperty("trades",""+trades.size());n=0;for(Trade t:trades.values()){String k="t."+(n++)+".";
            p.setProperty(k+"instrument",t.instrument);p.setProperty(k+"side",t.side);p.setProperty(k+"entry",t.entryTag);p.setProperty(k+"stop",""+t.stop);
            p.setProperty(k+"created",t.created.toString());p.setProperty(k+"exits",String.join(",",t.exits));p.setProperty(k+"reason",t.reason);}
        journal.save(p);
    }
    private void restore(Properties p){runId=p.getProperty("runId");
        if(!p.getProperty("messages","").isEmpty())messages.addAll(Arrays.asList(p.getProperty("messages").split("\n")));
        String a=p.getProperty("attempted","");if(!a.isBlank())attempted.addAll(Arrays.asList(a.split(",")));
        for(int i=0;i<Integer.parseInt(p.getProperty("orders"));i++){String k="o."+i+".";
            Tracked o=new Tracked(new Broker.Request(p.getProperty(k+"instrument"),p.getProperty(k+"side"),p.getProperty(k+"type"),Integer.parseInt(p.getProperty(k+"quantity")),Double.parseDouble(p.getProperty(k+"price")),Double.parseDouble(p.getProperty(k+"trigger")),p.getProperty(k+"tag")));
            o.brokerId=p.getProperty(k+"id");o.status=p.getProperty(k+"status");o.pending=p.getProperty(k+"pending");o.filled=Integer.parseInt(p.getProperty(k+"filled"));o.average=Double.parseDouble(p.getProperty(k+"average"));orders.put(o.request.tag,o);}
        for(int i=0;i<Integer.parseInt(p.getProperty("trades"));i++){String k="t."+i+".";Trade t=new Trade();
            t.instrument=p.getProperty(k+"instrument");t.side=p.getProperty(k+"side");t.entryTag=p.getProperty(k+"entry");t.stop=Double.parseDouble(p.getProperty(k+"stop"));t.created=Instant.parse(p.getProperty(k+"created"));
            t.reason=p.getProperty(k+"reason");String xs=p.getProperty(k+"exits","");if(!xs.isBlank())t.exits.addAll(Arrays.asList(xs.split(",")));trades.put(t.instrument,t);}
    }
}
