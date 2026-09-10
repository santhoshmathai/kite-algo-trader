package com.example.trading.ibkr;

import com.example.trading.app.*;
import com.example.trading.broker.Broker;
import com.example.trading.storage.Journal;
import com.ib.client.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Official socket SDK adapter. Every mutation is bound to one explicitly selected DU account. */
public final class IbkrPaperBroker implements Broker {
    public record Bar(String instrument,long epoch,double open,double high,double low,double close,long volume,Instant received) { }
    public record Quote(double price,double bid,double ask,Instant at) { }
    private final IbkrSettings cfg;
    private final Journal ledger;
    private final EJavaSignal signal=new EJavaSignal();
    private final State state=new State();
    private final EClientSocket client=new EClientSocket(state,signal);
    private final Socket socket=new Socket();
    private final Map<String,Contract> contracts=new LinkedHashMap<>();
    private final Map<Integer,String> instruments=new HashMap<>();
    private final Map<Integer,Request> intents=new LinkedHashMap<>();
    private final Map<String,Integer> tags=new HashMap<>();
    private final Map<Integer,Long> permanentIds=new HashMap<>();
    private final BlockingQueue<Bar> bars=new ArrayBlockingQueue<>(4096);
    private final String context;
    private int requestId=10000;
    private Thread pump;
    private boolean closed;
    private double startingSettled=Double.NaN;

    /** Offline callback harness: it has no connected transport, so mutation methods always fail. */
    IbkrPaperBroker(IbkrSettings cfg,Journal ledger){this.cfg=cfg;this.ledger=ledger;this.context="OFFLINE";}
    DefaultEWrapper callbacks(){return state;}
    List<Broker.Order> observedOrders(){synchronized(state){return new ArrayList<>(state.orders.values());}}

    public IbkrPaperBroker(IbkrSettings cfg,Path ledgerPath,String context,boolean armed)throws Exception{
        requirePaper(cfg,armed);
        if(Runtime.version().feature()<21)throw new IllegalStateException("Use Java 21+ for an IBKR connection");
        this.cfg=cfg;this.context=context;ledger=new Journal(ledgerPath);
        try{
            Properties previous=ledger.latest();
            if(!previous.isEmpty()){
                if(!context.equals(previous.getProperty("context")))throw new IOException("IBKR order ledger context changed");
                startingSettled=Double.parseDouble(previous.getProperty("startingSettled","NaN"));
                for(String key:previous.stringPropertyNames()){
                    if(key.startsWith("execution."))state.executions.put(key.substring(10),previous.getProperty(key));
                    if(key.startsWith("commission."))state.commissions.put(key.substring(11),previous.getProperty(key));
                    if(key.startsWith("perm."))permanentIds.put(Integer.parseInt(key.substring(5)),Long.parseLong(previous.getProperty(key)));
                }
                for(String key:previous.stringPropertyNames())if(key.startsWith("order.")){
                    int id=Integer.parseInt(key.substring(6));String[] r=previous.getProperty(key).split(",");
                    Request request=new Request(r[0],r[1],r[2],Integer.parseInt(r[3]),Double.parseDouble(r[4]),Double.parseDouble(r[5]),r[6]);
                    intents.put(id,request);tags.put(request.tag,id);
                }
            }
            socket.connect(new InetSocketAddress(cfg.host,cfg.port),cfg.timeoutSeconds*1000);socket.setSoTimeout(cfg.timeoutSeconds*1000);
            client.eConnect(socket,cfg.clientId);socket.setSoTimeout(0);
            if(!client.isConnected())throw new IOException("IBKR socket handshake failed");
            EReader reader=new EReader(client,signal);reader.setDaemon(true);reader.start();
            pump=new Thread(()->{while(client.isConnected()&&!Thread.currentThread().isInterrupted()){
                signal.waitForSignal();try{reader.processMsgs();}catch(Exception e){state.error(e);break;}
            }},"ibkr-paper-events");pump.setDaemon(true);pump.start();
            await(()->state.nextId>=0&&state.accountsReceived);
            synchronized(state){if(!cfg.matches(state.accounts)||state.accounts.stream().anyMatch(a->!a.matches("DU[0-9]+")))
                throw new IOException("Expected paper account missing or session contains a non-paper account");
                for(int id:intents.keySet())state.nextId=Math.max(state.nextId,id+1);
            }
            save();
        }catch(Exception e){close();throw e;}
    }
    static void requirePaper(IbkrSettings cfg,boolean armed){
        if(!armed||!cfg.expectedAccount.matches("DU[0-9]+")||!Set.of(7497,4002).contains(cfg.port)||!cfg.host.equals("127.0.0.1"))
            throw new IllegalArgumentException("Paper execution requires --arm-paper and an exact DU account on a loopback paper port");
    }
    private void healthy()throws IOException{
        if(closed||!client.isConnected()||!state.failure.isEmpty())throw new IOException("IBKR disconnected/unhealthy: "+state.failure);
    }
    private void await(BooleanSupplier ready)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(cfg.timeoutSeconds);
        synchronized(state){while(!ready.getAsBoolean()){healthy();long left=deadline-System.nanoTime();if(left<=0)throw new IOException("IBKR response timeout; no automatic request retry");state.wait(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left))));}healthy();}
    }
    private void save()throws IOException{
        Properties p=new Properties();p.setProperty("context",context);
        p.setProperty("startingSettled",""+startingSettled);
        state.executions.forEach((id,line)->p.setProperty("execution."+id,line));
        state.commissions.forEach((id,line)->p.setProperty("commission."+id,line));
        permanentIds.forEach((id,perm)->p.setProperty("perm."+id,""+perm));
        for(var e:intents.entrySet()){Request r=e.getValue();p.setProperty("order."+e.getKey(),r.instrument+","+r.side+","+r.type+","+r.quantity+","+r.price+","+r.trigger+","+r.tag);}
        ledger.save(p);
    }
    public Map<String,Equity> qualify(Path universe,Set<String> symbols,LocalDate day)throws Exception{
        Map<String,Equity> assets=new LinkedHashMap<>();
        for(String[] row:Csv.read(universe,"symbol,primary_exchange")){
            if(row.length!=2)throw new IOException("Invalid US universe row");if(!symbols.contains(row[0]))continue;
            if(!Set.of("NASDAQ","NYSE","ARCA","AMEX","BATS").contains(row[1]))throw new IOException("Unsupported primary exchange");
            Contract query=new Contract();query.symbol(row[0]);query.secType("STK");query.currency("USD");query.exchange("SMART");query.primaryExch(row[1]);
            int req=++requestId;synchronized(state){state.details.clear();state.detailsDone=-1;}
            client.reqContractDetails(req,query);await(()->state.detailsDone==req);
            synchronized(state){
                if(state.details.size()!=1)throw new IOException("Contract ambiguous or absent for "+row[0]);
                ContractDetails d=state.details.get(0);Contract c=d.contract();
                if(c.conid()<=0||!c.currency().equals("USD")||!c.secType().toString().equals("STK")||!c.symbol().equals(row[0])||!c.primaryExch().equals(row[1]))throw new IOException("Contract identity mismatch");
                // Restrict this first version to penny-tick US equities; price-dependent rules below $1 are excluded at entry.
                if(!Double.isFinite(d.minTick())||d.minTick()<=0||d.minTick()>.01)throw new IOException("Unsupported tick increment");
                c.exchange("SMART");Equity e=new Equity("IBKR",row[0],c.conid(),.01,"US",day);
                if(assets.put(e.id,e)!=null||instruments.put(c.conid(),e.id)!=null)throw new IOException("Duplicate US contract");contracts.put(e.id,c);
            }
        }
        if(assets.size()!=symbols.size())throw new IOException("Universe must cover every configured symbol");return assets;
    }
    public void subscribe(){
        client.reqMarketDataType(1);
        synchronized(state){for(var entry:contracts.entrySet()){
            int req=++requestId;state.subscriptions.put(req,entry.getKey());state.quotes.put(entry.getKey(),new Prices());
            client.reqMktData(req,entry.getValue(),"",false,false,null);
            client.reqRealTimeBars(req,entry.getValue(),5,"TRADES",true,null);
        }}
    }
    public Bar nextBar(){return bars.poll();}
    public String dataFailure(){return state.dataFailure;}
    public Quote quote(String instrument){synchronized(state){Prices p=state.quotes.get(instrument);
        if(p==null||!p.live||p.bidAt==null||p.askAt==null||p.lastAt==null)return null;
        Instant oldest=Collections.min(List.of(p.bidAt,p.askAt,p.lastAt));return new Quote(p.last,p.bid,p.ask,oldest);
    }}
    @Override public boolean cancelBeforeExitTypeChange(){return true;}
    static com.ib.client.Order sdkOrder(Request r,String account){
        com.ib.client.Order o=new com.ib.client.Order();o.account(account);o.action(r.side);o.orderType(switch(r.type){case "LIMIT"->"LMT";case "SL-M"->"STP";default->"MKT";});
        o.totalQuantity(Decimal.get(r.quantity));o.tif("DAY");o.outsideRth(false);o.transmit(true);o.orderRef(r.tag);
        if(r.type.equals("LIMIT"))o.lmtPrice(r.price);if(r.type.equals("SL-M"))o.auxPrice(r.trigger);return o;
    }
    @Override public String submit(Request r)throws Exception{
        synchronized(state){healthy();requirePaper(cfg,true);
            if(!contracts.containsKey(r.instrument)||tags.containsKey(r.tag))throw new IOException("Unknown contract or duplicate intent; no resubmission");
            if(r.side.equals("BUY")&&(!r.type.equals("LIMIT")||r.price<1||!state.dataFailure.isEmpty()))throw new IOException("Only long penny-tick equity entries with healthy data supported");
            int id=state.nextId++;intents.put(id,r);tags.put(r.tag,id);save(); // Durable identity BEFORE SDK transmission.
            client.placeOrder(id,contracts.get(r.instrument),sdkOrder(r,cfg.expectedAccount));return Integer.toString(id);
        }
    }
    @Override public void cancel(String id)throws Exception{synchronized(state){healthy();int n=Integer.parseInt(id);if(!intents.containsKey(n))throw new IOException("Cannot cancel unmanaged order");client.cancelOrder(n,new OrderCancel());}}
    @Override public void modify(String id,Request r)throws Exception{synchronized(state){healthy();int n=Integer.parseInt(id);Request old=intents.get(n);
        if(old==null||!old.tag.equals(r.tag)||!old.instrument.equals(r.instrument)||!old.side.equals(r.side)||!old.type.equals(r.type))throw new IOException("Unsafe IBKR modification");
        intents.put(n,r);save();client.placeOrder(n,contracts.get(r.instrument),sdkOrder(r,cfg.expectedAccount));
    }}
    @Override public Snapshot snapshot()throws Exception{
        synchronized(state){healthy();state.openDone=false;}client.reqAllOpenOrders();await(()->state.openDone);
        synchronized(state){state.completedDone=false;}client.reqCompletedOrders(false);await(()->state.completedDone);
        int req=++requestId;ExecutionFilter filter=new ExecutionFilter();filter.acctCode(cfg.expectedAccount);
        client.reqExecutions(req,filter);await(()->state.execDone==req);
        synchronized(state){state.positions.clear();state.positionDone=false;}client.reqPositions();await(()->state.positionDone);client.cancelPositions();
        synchronized(state){state.accountDone=false;state.cash=Double.NaN;state.accountType="";}
        client.reqAccountUpdates(true,cfg.expectedAccount);await(()->state.accountDone);client.reqAccountUpdates(false,cfg.expectedAccount);
        int summary=++requestId;synchronized(state){state.settled=Double.NaN;}
        client.reqAccountSummary(summary,"All","SettledCash");await(()->state.summaryDone==summary);client.cancelAccountSummary(summary);
        synchronized(state){healthy();if(!Double.isFinite(state.cash)||state.cash<0)throw new IOException("USD TotalCashBalance unavailable");
            if(!Double.isFinite(state.settled)||state.settled<0){state.dataFailure="USD settled cash unavailable: use a funded USD-base paper account";state.settled=0;}
            if(!Double.isFinite(startingSettled))startingSettled=state.settled;
            // Do not recycle today's sale proceeds; cash-only sizing remains conservative on a margin account too.
            double spent=0;for(String line:state.executions.values()){String[] x=line.split(",");if(x[4].equals("BOT")&&tags.containsKey(x[2]))spent+=Double.parseDouble(x[5])*Double.parseDouble(x[6]);}
            double available=Math.max(0,Math.min(Math.min(state.cash,state.settled),startingSettled-spent));
            save();
            List<Broker.Order> result=new ArrayList<>(state.orders.values());
            return new Snapshot(result,state.positions,available);
        }
    }
    public List<String> executionLines(){synchronized(state){List<String> result=new ArrayList<>();state.executions.forEach((id,line)->result.add(line+","+state.commissions.getOrDefault(id,",")));return result;}}
    @Override public void close()throws Exception{if(closed)return;closed=true;try{socket.close();}finally{client.eDisconnect();signal.issueSignal();if(pump!=null)pump.interrupt();ledger.close();}}
    private String identity(Contract c){return instruments.getOrDefault(c.conid(),"UNMANAGED:"+c.conid());}
    static int whole(Decimal value){return new java.math.BigDecimal(value.toString()).intValueExact();}
    static String status(String s){return switch(s){case "Filled"->"COMPLETE";case "Cancelled","ApiCancelled"->"CANCELLED";default->"OPEN";};}
    private static final class Prices {double last,bid,ask;Instant lastAt,bidAt,askAt;boolean live;}
    private final class State extends DefaultEWrapper {
        volatile String failure="",dataFailure="";
        int nextId=-1,detailsDone=-1,execDone=-1;boolean accountsReceived,openDone,completedDone,positionDone,accountDone;
        double cash=Double.NaN,settled=Double.NaN;int summaryDone=-1;String accountType="";
        final Set<String> accounts=new HashSet<>();final List<ContractDetails> details=new ArrayList<>();
        final Map<Integer,Broker.Order> orders=new LinkedHashMap<>();
        final Map<Integer,Fill> fills=new HashMap<>();
        final Map<String,Integer> positions=new HashMap<>();
        final Map<Integer,String> subscriptions=new HashMap<>();final Map<String,Prices> quotes=new HashMap<>();
        final Map<String,String> executions=new LinkedHashMap<>();
        final Map<String,String> commissions=new LinkedHashMap<>();
        @Override public synchronized void nextValidId(int n){nextId=Math.max(nextId,n);notifyAll();}
        @Override public synchronized void managedAccounts(String list){accounts.clear();for(String s:list.split(","))accounts.add(s.trim());accountsReceived=true;notifyAll();}
        @Override public synchronized void connectionClosed(){failure="Connection closed: inspect TWS and restart in recovery mode";notifyAll();}
        @Override public synchronized void error(Exception e){failure="Transport/callback error "+e.getClass().getSimpleName();notifyAll();}
        @Override public synchronized void error(String s){failure="SDK error; inspect local TWS API log";notifyAll();}
        @Override public synchronized void error(int id,long time,int code,String message,String advanced){
            if(Set.of(2104,2106,2107,2108,2158).contains(code))return;
            if(code==201&&intents.containsKey(id)){Request r=intents.get(id);Fill f=fills.getOrDefault(id,new Fill(0,0,"REJECTED"));orders.put(id,new Broker.Order(""+id,r.tag,r.instrument,r.side,r.type,"REJECTED",r.quantity,f.quantity,f.average,r.price,r.trigger));}
            else if(code!=202)dataFailure="IBKR error code "+code+"; inspect local API log";
            if(Set.of(502,503,504,326,1100,1101,1102,1300).contains(code))failure="IBKR connectivity error "+code+"; recovery required";
            notifyAll();
        }
        @Override public synchronized void contractDetails(int id,ContractDetails d){details.add(d);}
        @Override public synchronized void contractDetailsEnd(int id){detailsDone=id;notifyAll();}
        @Override public synchronized void openOrder(int id,Contract c,com.ib.client.Order o,OrderState os){capture(id,c,o,status(os.status().toString()));}
        @Override public synchronized void completedOrder(Contract c,com.ib.client.Order o,OrderState os){
            Integer known=tags.get(o.orderRef());int id=known==null?o.orderId():known;
            capture(id,c,o,status(os.status().toString()));
        }
        private void capture(int id,Contract c,com.ib.client.Order o,String s){
            if(!cfg.expectedAccount.equals(o.account()))return;
            if(o.clientId()!=cfg.clientId){if(!Set.of("COMPLETE","CANCELLED","REJECTED").contains(s))orders.put(-Math.abs(id)-1,new Broker.Order("foreign","",identity(c),"","", "OPEN",0,0,0,0,0));return;}
            bindPermanent(id,o.permId());
            String type=switch(o.orderType().toString()){case "LMT"->"LIMIT";case "STP"->"SL-M";case "MKT"->"MARKET";default->"UNSUPPORTED";};
            Fill f=fills.getOrDefault(id,new Fill(0,0,s));Broker.Order old=orders.get(id);
            if(Set.of("COMPLETE","CANCELLED","REJECTED").contains(f.status))s=f.status;
            if(old!=null&&old.terminal()&&!Set.of("COMPLETE","CANCELLED","REJECTED").contains(s))s=old.status;
            if(f.quantity==whole(o.totalQuantity()))s="COMPLETE";
            orders.put(id,new Broker.Order(""+id,o.orderRef(),identity(c),o.action().toString(),type,s,whole(o.totalQuantity()),f.quantity,f.average,type.equals("LIMIT")?o.lmtPrice():0,type.equals("SL-M")?o.auxPrice():0));
            nextId=Math.max(nextId,id+1);
        }
        @Override public synchronized void orderStatus(int id,String s,Decimal filled,Decimal remaining,double avg,long perm,int parent,double last,int clientId,String why,double cap){
            if(clientId!=cfg.clientId)return;int q=whole(filled);Fill old=fills.get(id);
            bindPermanent(id,perm);
            if(s.equals("Inactive"))dataFailure="Inactive order: inspect TWS; no assumption of rejection";
            if(old!=null&&q<old.quantity){failure="Regressive fill callback";return;}
            fills.put(id,new Fill(q,avg,status(s)));Broker.Order o=orders.get(id);
            if(o!=null)orders.put(id,new Broker.Order(o.id,o.tag,o.instrument,o.side,o.type,status(s),o.quantity,q,avg,o.price,o.trigger));
        }
        @Override public synchronized void openOrderEnd(){openDone=true;notifyAll();}
        @Override public synchronized void completedOrdersEnd(){completedDone=true;notifyAll();}
        @Override public synchronized void execDetails(int req,Contract c,Execution e){
            if(!cfg.expectedAccount.equals(e.acctNumber()))return;
            int dot=e.execId().lastIndexOf('.');
            if(dot>=0)for(String prior:executions.keySet())if(!prior.equals(e.execId())&&prior.startsWith(e.execId().substring(0,dot+1)))failure="Execution correction ID requires operator reconciliation";
            String line=e.execId()+","+e.orderId()+","+e.orderRef()+","+identity(c)+","+e.side()+","+e.shares()+","+e.price()+","+e.time();
            String old=executions.putIfAbsent(e.execId(),line);if(old!=null&&!old.equals(line))failure="Execution correction requires operator reconciliation";
            if(e.pendingPriceRevision())failure="Pending execution price revision";
            if(e.clientId()!=cfg.clientId)return;
            bindPermanent(e.orderId(),e.permId());
            int q=whole(e.cumQty());Fill f=fills.get(e.orderId());
            if(f!=null&&q==f.quantity&&q>0&&Math.abs(f.average-e.avgPrice())>1e-6)failure="Execution average correction requires reconciliation";
            if(f==null||q>f.quantity){fills.put(e.orderId(),new Fill(q,e.avgPrice(),"OPEN"));Broker.Order o=orders.get(e.orderId());
                if(o!=null)orders.put(e.orderId(),new Broker.Order(o.id,o.tag,o.instrument,o.side,o.type,q==o.quantity?"COMPLETE":o.status,o.quantity,q,e.avgPrice(),o.price,o.trigger));}
        }
        @Override public synchronized void execDetailsEnd(int id){execDone=id;notifyAll();}
        private void bindPermanent(int id,long perm){if(perm<=0)return;Long old=permanentIds.putIfAbsent(id,perm);if(old!=null&&old!=perm)failure="Order ID reused with a different broker permanent ID";}
        @Override public synchronized void commissionAndFeesReport(CommissionAndFeesReport report){
            if(Double.isFinite(report.commissionAndFees())&&Math.abs(report.commissionAndFees())<1e20)
                commissions.put(report.execId(),report.commissionAndFees()+","+report.currency());
        }
        @Override public synchronized void accountSummary(int id,String account,String tag,String value,String currency){
            if(account.equals(cfg.expectedAccount)&&tag.equals("SettledCash")&&currency.equals("USD"))settled=Double.parseDouble(value);
        }
        @Override public synchronized void accountSummaryEnd(int id){summaryDone=id;notifyAll();}
        @Override public synchronized void position(String account,Contract c,Decimal p,double average){if(account.equals(cfg.expectedAccount)){int q=whole(p);if(q==0)positions.remove(identity(c));else positions.put(identity(c),q);}}
        @Override public synchronized void positionEnd(){positionDone=true;notifyAll();}
        @Override public synchronized void updateAccountValue(String key,String value,String currency,String account){
            if(!account.equals(cfg.expectedAccount))return;
            if(key.equals("TotalCashBalance")&&currency.equals("USD"))cash=Double.parseDouble(value);
            if(key.equals("AccountType"))accountType=value;
        }
        @Override public synchronized void accountDownloadEnd(String account){if(account.equals(cfg.expectedAccount)){accountDone=true;notifyAll();}}
        @Override public synchronized void marketDataType(int req,int type){String id=subscriptions.get(req);if(id!=null){quotes.get(id).live=type==1;if(type!=1)dataFailure="Non-live market data rejected";}}
        @Override public synchronized void tickPrice(int req,int field,double price,TickAttrib attrib){String id=subscriptions.get(req);if(id==null)return;Prices p=quotes.get(id);Instant now=Instant.now();
            if(field==1){p.bid=price;p.bidAt=now;}if(field==2){p.ask=price;p.askAt=now;}if(field==4){p.last=price;p.lastAt=now;}
        }
        @Override public synchronized void realtimeBar(int req,long epoch,double o,double h,double l,double c,Decimal volume,Decimal wap,int count){String id=subscriptions.get(req);if(id==null)return;
            // Leave raw volume unscaled: safe lower bound if this TWS reports US stock volume in lots.
            long v=new java.math.BigDecimal(volume.toString()).longValueExact();
            if(!bars.offer(new Bar(id,epoch,o,h,l,c,v,Instant.now())))dataFailure="Market-data queue overflow";
        }
    }
    private record Fill(int quantity,double average,String status) { }
}
