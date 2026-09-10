package com.example.trading.ibkr;

import com.example.trading.app.*;
import com.example.trading.broker.*;
import com.example.trading.core.*;
import com.example.trading.storage.Journal;
import com.ib.client.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline execution contracts, including a fill racing a stop cancellation. */
public final class IbkrPaperChecks {
    static int passed;
    interface Check {void run()throws Exception;}
    static void check(String name,Check c)throws Exception{c.run();passed++;System.out.println("PASS "+name);}
    static void yes(boolean b){if(!b)throw new AssertionError();}
    static void fails(Check c)throws Exception{try{c.run();}catch(Exception e){return;}throw new AssertionError("Expected failure");}
    static final LocalDate DAY=LocalDate.of(2026,9,8);
    static final Instant OPEN=DAY.atTime(9,30).atZone(UsCalendar.ZONE).toInstant();
    static UsPaperProfile profile()throws Exception{return new UsPaperProfile(Path.of("config/ibkr/us-paper.properties"));}
    static Path temp()throws Exception{return Files.createTempDirectory(Path.of("build"),"us-paper-check-");}
    static final class Fake implements Broker {
        final Map<String,Order> orders=new LinkedHashMap<>();int sequence;String cancelPending="";boolean race,partialRace,uncertain,rejectExit;
        public String submit(Request r)throws Exception{
            String id=""+(++sequence);orders.put(id,new Order(id,r.tag,r.instrument,r.side,r.type,rejectExit&&r.side.equals("SELL")?"REJECTED":"OPEN",r.quantity,0,0,r.price,r.trigger));
            if(uncertain)throw new java.io.IOException("lost acknowledgement");return id;
        }
        public void cancel(String id){cancelPending=id;}
        public void modify(String id,Request r){throw new AssertionError("This fixture requires cancel then replace");}
        public boolean cancelBeforeExitTypeChange(){return true;}
        void fill(String id,int count,double average,String status){Order o=orders.get(id);orders.put(id,new Order(id,o.tag,o.instrument,o.side,o.type,status,o.quantity,count,average,o.price,o.trigger));}
        void confirmCancel(){if(cancelPending.isEmpty())return;Order o=orders.get(cancelPending);
            if(race)fill(o.id,partialRace?1:o.quantity,o.trigger,"CANCELLED");else fill(o.id,o.filled,o.average,"CANCELLED");cancelPending="";
        }
        public Snapshot snapshot(){Map<String,Integer> positions=new HashMap<>();for(Order o:orders.values())positions.merge(o.instrument,o.side.equals("BUY")?o.filled:-o.filled,Integer::sum);positions.values().removeIf(n->n==0);return new Snapshot(new ArrayList<>(orders.values()),positions,10000);}
    }
    static final class Rig implements AutoCloseable {
        final Fake broker=new Fake();final Journal journal;final TradingEngine engine;Instant time=OPEN;
        Rig()throws Exception{journal=new Journal(temp().resolve("engine.journal"));Equity equity=new Equity("IBKR","DEMO",1,.01,"US",DAY);
            engine=new TradingEngine(profile().risk,DAY,Map.of(equity.id,equity),broker,journal,UsPaperProfile.COSTS,"test",new TradingSession(Set.of(),UsCalendar.ZONE,LocalTime.of(9,30),LocalTime.of(16,0)));}
        void signal()throws Exception{for(int n=0;n<16;n++){time=OPEN.plusSeconds((n+1)*60L);double p=n==15?102:100;engine.mark("IBKR:DEMO",p,p-.01,p+.01,time);engine.poll(time);
            engine.closed(new Candle(OPEN.plusSeconds(n*60L).atZone(UsCalendar.ZONE),"IBKR:DEMO",100,n==15?102:101,99,p,100000,UsCalendar.ZONE),time);}
        }
        TradingEngine.Trade trade(){return engine.trades().get(0);}
        void poll(double price)throws Exception{time=time.plusSeconds(1);engine.mark("IBKR:DEMO",price,price-.01,price+.01,time);engine.poll(time);}
        void bought()throws Exception{signal();int q=broker.orders.get("1").quantity;broker.fill("1",q,102,"COMPLETE");poll(102);poll(102);}
        public void close()throws Exception{journal.close();}
    }
    static OrderState orderState()throws Exception{var constructor=OrderState.class.getDeclaredConstructor();constructor.setAccessible(true);return constructor.newInstance();}
    static Contract contract(){Contract c=new Contract();c.conid(123);c.symbol("AAPL");c.secType("STK");c.currency("USD");return c;}
    static com.ib.client.Order order(){com.ib.client.Order o=IbkrPaperBroker.sdkOrder(new Broker.Request("IBKR:AAPL","BUY","LIMIT",5,100,0,"TEST1"),"DU123456");o.clientId(71);o.orderId(10);return o;}
    static IbkrPaperBroker callbackBroker()throws Exception{return new IbkrPaperBroker(IbkrChecks.config("expectedAccount=UNSET","expectedAccount=DU123456"),new Journal(temp().resolve("callbacks.journal")));}
    public static void main(String[] args)throws Exception{
        check("US profile is explicit USD long-only with US exit times",()->{yes(profile().risk.capital==10000);fails(()->new UsPaperProfile(Path.of("config/paper-500k.properties")));});
        check("paper account and arm gates reject before opening a socket",()->{fails(()->IbkrPaperBroker.requirePaper(IbkrChecks.config(null,null),true));fails(()->IbkrPaperBroker.requirePaper(IbkrChecks.config("expectedAccount=UNSET","expectedAccount=DU123456"),false));});
        check("SDK translation binds account reference DAY and regular-hours orders",()->{var r=new Broker.Request("IBKR:AAPL","SELL","SL-M",3,0,99,"TAG1");var o=IbkrPaperBroker.sdkOrder(r,"DU123456");yes(o.account().equals("DU123456")&&o.orderType().toString().equals("STP")&&o.auxPrice()==99&&!o.outsideRth()&&o.orderRef().equals("TAG1")&&o.tif().toString().equals("DAY"));});
        check("Inactive is not assumed terminal and fractional positions fail closed",()->{yes(IbkrPaperBroker.status("Inactive").equals("OPEN"));fails(()->IbkrPaperBroker.whole(Decimal.get(.5)));});
        check("minute bars require all twelve observations and retain US zone",()->{MinuteBars m=new MinuteBars();Optional<Candle> c=Optional.empty();for(int n=0;n<12;n++)c=m.accept("IBKR:AAPL",OPEN.getEpochSecond()+n*5,100,101,99,100,10);yes(c.orElseThrow().getVolume()==120&&c.get().getTimestamp().getHour()==9);});
        check("missing and duplicate five-second bars cannot create a signal candle",()->{MinuteBars m=new MinuteBars();for(int n=1;n<12;n++)yes(m.accept("IBKR:AAPL",OPEN.getEpochSecond()+n*5,100,101,99,100,10).isEmpty());fails(()->m.accept("IBKR:AAPL",OPEN.getEpochSecond()+55,100,101,99,100,10));});
        check("fills received before openOrder are retained without duplication",()->{try(var b=callbackBroker()){var w=b.callbacks();w.orderStatus(10,"Submitted",Decimal.get(2),Decimal.get(3),100,22,0,100,71,"",0);OrderState s=orderState();s.status("Submitted");w.openOrder(10,contract(),order(),s);w.openOrder(10,contract(),order(),s);yes(b.observedOrders().size()==1&&b.observedOrders().get(0).filled==2);}});
        check("executions recover missing fill status and are deduplicated",()->{try(var b=callbackBroker()){var w=b.callbacks();OrderState s=orderState();s.status("Submitted");w.openOrder(10,contract(),order(),s);
            Execution e=new Execution();e.execId("EXEC1");e.orderId(10);e.clientId(71);e.acctNumber("DU123456");e.orderRef("TEST1");e.side("BOT");e.shares(Decimal.get(5));e.cumQty(Decimal.get(5));e.price(100);e.avgPrice(100);e.time("20260908 09:46:01");w.execDetails(1,contract(),e);w.execDetails(1,contract(),e);
            yes(b.observedOrders().get(0).status.equals("COMPLETE")&&b.executionLines().size()==1);CommissionAndFeesReport f=new CommissionAndFeesReport();f.execId("EXEC1");f.commissionAndFees(1);f.currency("USD");w.commissionAndFeesReport(f);yes(b.executionLines().get(0).endsWith(",1.0,USD"));}});
        check("other-client active order is unmanaged and other account is ignored",()->{try(var b=callbackBroker()){var w=b.callbacks();OrderState s=orderState();s.status("Submitted");var o=order();o.account("DU999999");w.openOrder(10,contract(),o,s);yes(b.observedOrders().isEmpty());o.account("DU123456");o.clientId(72);w.openOrder(10,contract(),o,s);yes(b.observedOrders().get(0).tag.isEmpty());}});
        check("offline callback harness cannot submit or cancel",()->{try(var b=callbackBroker()){fails(()->b.submit(new Broker.Request("IBKR:AAPL","BUY","LIMIT",1,100,0,"T1")));fails(()->b.cancel("10"));}});
        check("US engine sizes within USD capital notional and trade-risk caps",()->{try(Rig r=new Rig()){r.signal();var o=r.broker.orders.get("1");yes(o.quantity>0&&o.quantity*o.price<=profile().risk.maxNotional&&o.quantity*(o.price-99+2)<=profile().risk.tradeRisk);}});
        check("target waits for stop cancellation before replacement sell",()->{try(Rig r=new Rig()){r.bought();r.poll(108);yes(r.broker.sequence==2&&!r.broker.cancelPending.isEmpty());r.poll(108);yes(r.broker.sequence==2);r.broker.confirmCancel();r.poll(108);yes(r.broker.sequence==3&&r.broker.orders.get("3").type.equals("MARKET"));}});
        check("stop fill racing cancel suppresses a duplicate sell",()->{try(Rig r=new Rig()){r.bought();r.broker.race=true;r.poll(108);r.broker.confirmCancel();r.poll(108);yes(r.broker.sequence==2&&r.engine.flat());}});
        check("partial stop fill racing cancel sells only remaining shares",()->{try(Rig r=new Rig()){r.bought();r.broker.race=true;r.broker.partialRace=true;r.poll(108);r.broker.confirmCancel();r.poll(108);yes(r.broker.orders.get("3").quantity==r.broker.orders.get("1").quantity-1);}});
        check("partial entry cancels remainder and protects filled quantity",()->{try(Rig r=new Rig()){r.signal();r.broker.fill("1",1,102,"OPEN");r.poll(102);yes(r.broker.orders.get("2").quantity==1&&r.broker.cancelPending.equals("1"));r.broker.confirmCancel();r.poll(102);yes(r.engine.remaining(r.trade())==1);}});
        check("uncertain submit is reconciled without retrying entry",()->{try(Rig r=new Rig()){r.broker.uncertain=true;r.signal();r.poll(102);yes(r.engine.halted()&&r.broker.sequence==1);}});
        check("protective rejection halts without repeated sell submissions",()->{try(Rig r=new Rig()){r.broker.rejectExit=true;r.bought();r.poll(102);yes(r.engine.halted()&&r.broker.sequence==2);}});
        check("US time flatten begins at 10:30 New York",()->{try(Rig r=new Rig()){r.bought();r.time=DAY.atTime(10,30).atZone(UsCalendar.ZONE).toInstant().minusSeconds(1);r.poll(102);yes(!r.broker.cancelPending.isEmpty()&&!r.engine.finished());}});
        check("offline demo completes automated entry stop cancellation and sell",()->{Path dir=temp();UsPaperDemo.run(profile(),dir);Properties p=new Properties();try(var in=Files.newBufferedReader(dir.resolve("session.properties"))){p.load(in);}yes(p.getProperty("confirmedFlat").equals("true")&&p.getProperty("mode").equals("OFFLINE_DEMO"));});
        System.out.println("IBKR paper checks passed: "+passed);
    }
}
