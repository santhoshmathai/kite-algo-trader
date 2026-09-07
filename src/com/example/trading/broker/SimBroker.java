package com.example.trading.broker;

import com.example.trading.core.Candle;
import java.util.*;

/** Conservative bar simulator: entries at a subsequent open only, stops before target decisions. */
public final class SimBroker implements Broker {
    private static final class State { String id,status="OPEN";Request request;int filled;double value;long created;boolean triggered; }
    private final Map<String,State> orders=new LinkedHashMap<>();
    private final Map<String,Integer> positions=new HashMap<>();
    private final double capital,slippage,participation;
    private long sequence, time;
    private final Map<String,Long> budgetTime=new HashMap<>();
    private final Map<String,Integer> budgetLeft=new HashMap<>();
    public SimBroker(double capital,double slippageBps,double participation){this.capital=capital;slippage=slippageBps/10000;this.participation=participation;}
    public void time(long epochMillis){time=epochMillis;}
    @Override public String submit(Request request){State s=new State();s.id="paper"+(++sequence);s.request=request;s.created=time;if(request.type.equals("SL-M"))s.status="TRIGGER PENDING";orders.put(s.id,s);return s.id;}
    @Override public void cancel(String id){State s=orders.get(id);if(s==null)throw new IllegalArgumentException("Unknown order");if(!terminal(s))s.status="CANCELLED";}
    @Override public void modify(String id,Request request){State s=orders.get(id);if(s==null||terminal(s)||request.quantity<s.filled)throw new IllegalArgumentException("Cannot modify");s.request=request;s.status=request.type.equals("SL-M")?"TRIGGER PENDING":"OPEN";}
    private boolean terminal(State s){return Set.of("COMPLETE","CANCELLED","REJECTED").contains(s.status);}
    public void bar(Candle c){open(c);range(c);}
    public void open(Candle c){process(c,false);}
    public void range(Candle c){process(c,true);}
    private void process(Candle c,boolean range){
        long open=c.getTimestamp().toInstant().toEpochMilli();time=open;
        if(!Objects.equals(budgetTime.get(c.getInstrumentToken()),open)){
            budgetTime.put(c.getInstrumentToken(),open);budgetLeft.put(c.getInstrumentToken(),(int)Math.min(Integer.MAX_VALUE,Math.floor(c.getVolume()*participation)));}
        int budget=budgetLeft.get(c.getInstrumentToken());
        // Existing protection is processed before entry orders on the same bar.
        List<State> ordered=new ArrayList<>(orders.values());ordered.sort(Comparator.comparing(s->s.request.type.equals("LIMIT")?1:0));
        for(State s:ordered){Request r=s.request;if(terminal(s)||!r.instrument.equals(c.getInstrumentToken())||s.created>open)continue;
            boolean buy=r.side.equals("BUY");double price=buy?c.getOpen()*(1+slippage):c.getOpen()*(1-slippage);
            boolean execute=!range&&r.type.equals("MARKET");
            if(r.type.equals("LIMIT"))execute=!range&&(buy?price<=r.price:price>=r.price);
            if(r.type.equals("SL-M")) {
                boolean hit=range?(buy?c.getHigh()>=r.trigger:c.getLow()<=r.trigger):(buy?c.getOpen()>=r.trigger:c.getOpen()<=r.trigger);
                execute=s.triggered||hit;
                if(!s.triggered&&hit)price=(buy?Math.max(c.getOpen(),r.trigger):Math.min(c.getOpen(),r.trigger))*(buy?1+slippage:1-slippage);
                if(hit)s.triggered=true;
            }
            if(!execute)continue;
            int q=Math.min(r.quantity-s.filled,budget);if(q<=0)continue;budget-=q;
            s.value+=q*price;s.filled+=q;s.status=s.filled==r.quantity?"COMPLETE":"OPEN";
            positions.merge(r.instrument,buy?q:-q,Integer::sum);
        }
        budgetLeft.put(c.getInstrumentToken(),budget);
    }
    /** Paper ticks are deliberately small synthetic bars; no historical depth claim is made. */
    public void tick(String instrument,java.time.Instant at,double price,long volume){
        bar(new Candle(at.atZone(com.example.trading.app.SessionCalendar.ZONE),instrument,price,price,price,price,volume));
    }
    @Override public Snapshot snapshot(){List<Order> result=new ArrayList<>();for(State s:orders.values()){Request r=s.request;result.add(new Order(s.id,r.tag,r.instrument,r.side,r.type,s.status,r.quantity,s.filled,s.filled==0?0:s.value/s.filled,r.price,r.trigger));}
        Map<String,Integer> open=new HashMap<>();positions.forEach((k,v)->{if(v!=0)open.put(k,v);});return new Snapshot(result,open,capital);}
}
