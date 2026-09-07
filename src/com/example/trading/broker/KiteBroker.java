package com.example.trading.broker;

import com.example.trading.app.*;
import com.example.trading.core.Candle;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import com.zerodhatech.ticker.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Consumer;

/** All remote interactions go through the pinned official Kite SDK. Debug logging is disabled. */
public final class KiteBroker implements Broker {
    private final KiteConnect sdk;
    private final Map<String,Equity> assets;
    private final boolean executionEnabled;
    private long nextCall;
    private KiteTicker ticker;
    public final String account;
    private double cash;
    private long cashAt;
    public static final class Quote {
        public final String instrument;public final Instant time;public final double price,bid,ask;public final long volume;
        public Quote(String i,Instant t,double p,double bid,double ask,long v){instrument=i;time=t;price=p;this.bid=bid;this.ask=ask;volume=v;}
    }
    public KiteBroker(Settings settings,Map<String,Equity> assets,boolean executionEnabled)throws Exception{
        this.assets=Map.copyOf(assets);this.executionEnabled=executionEnabled;
        String api=secret("KITE_API_KEY");Properties session=new Properties();try(Reader r=Files.newBufferedReader(settings.path("sessionFile"))){session.load(r);}
        if(!api.equals(session.getProperty("apiKey")))throw new IOException("Session belongs to another API key");
        if(!LocalDate.now(SessionCalendar.ZONE).toString().equals(session.getProperty("date")))throw new IOException("Daily Kite login required");
        account=session.getProperty("userId");if(account==null||!account.matches("[A-Za-z0-9]+"))throw new IOException("Invalid session account");
        sdk=new KiteConnect(api,false);sdk.setUserId(account);sdk.setAccessToken(session.getProperty("accessToken"));
        try{sdk.getProfile();}catch(KiteException e){throw new IOException("Kite session validation failed ("+e.code+")");}
    }
    public static String loginUrl(){return new KiteConnect(secret("KITE_API_KEY"),false).getLoginURL();}
    public static void login(Settings cfg)throws Exception{
        String api=secret("KITE_API_KEY");KiteConnect sdk=new KiteConnect(api,false);
        User user;try{user=sdk.generateSession(secret("KITE_REQUEST_TOKEN"),secret("KITE_API_SECRET"));}catch(KiteException e){throw new IOException("Kite login failed ("+e.code+")");}
        Properties p=new Properties();p.setProperty("apiKey",api);p.setProperty("userId",user.userId);p.setProperty("accessToken",user.accessToken);p.setProperty("date",LocalDate.now(SessionCalendar.ZONE).toString());
        Path path=cfg.path("sessionFile");Files.createDirectories(path.getParent());
        try(Writer w=Files.newBufferedWriter(path,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){p.store(w,"Local secret: do not commit or share");}
        System.out.println("Session saved for account "+user.userId+"; token not printed.");
    }
    private static String secret(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalArgumentException("Set environment variable "+name);return v;}
    private synchronized void throttle()throws InterruptedException{long remaining=nextCall-System.nanoTime();if(remaining>0)Thread.sleep(Math.max(1,remaining/1000000));nextCall=System.nanoTime()+400000000L;}
    private OrderParams parameters(Request r){
        Equity e=assets.get(r.instrument);if(e==null)throw new IllegalArgumentException("Unknown NSE equity");
        OrderParams p=new OrderParams();p.exchange="NSE";p.tradingsymbol=e.symbol;p.product="MIS";p.validity="DAY";p.quantity=r.quantity;
        p.transactionType=r.side;p.orderType=r.type;p.tag=r.tag;
        if(r.type.equals("LIMIT"))p.price=r.price;
        if(r.type.equals("SL-M"))p.triggerPrice=r.trigger;
        if(r.type.equals("MARKET")||r.type.equals("SL-M"))p.marketProtection=-1;
        return p;
    }
    private void armed(){if(!executionEnabled)throw new IllegalStateException("This Kite connection is read-only");}
    @Override public String submit(Request r)throws Exception{armed();throttle();try{return sdk.placeOrder(parameters(r),"regular").orderId;}catch(KiteException e){throw new IOException("Kite order response "+e.code+"; reconcile before retry");}}
    @Override public void cancel(String id)throws Exception{armed();throttle();try{sdk.cancelOrder(id,"regular");}catch(KiteException e){throw new IOException("Kite cancel response "+e.code);}}
    @Override public void modify(String id,Request replacement)throws Exception{armed();throttle();try{sdk.modifyOrder(id,parameters(replacement),"regular");}catch(KiteException e){throw new IOException("Kite modify response "+e.code);}}
    @Override public Snapshot snapshot()throws Exception{
        try{
            throttle();List<com.zerodhatech.models.Order> all=sdk.getOrders();List<Broker.Order> normalized=new ArrayList<>();
            for(com.zerodhatech.models.Order o:all)normalized.add(new Broker.Order(o.orderId,o.tag,o.exchange+":"+o.tradingSymbol,o.transactionType,o.orderType,o.status,
                    Integer.parseInt(o.quantity),Integer.parseInt(o.filledQuantity),number(o.averagePrice),number(o.price),number(o.triggerPrice)));
            throttle();Map<String,Integer> positions=new HashMap<>();for(Position p:sdk.getPositions().get("net"))if(p.netQuantity!=0){
                // Unsupported product exposure must produce a reconciliation mismatch, never be silently netted with MIS.
                String key=p.exchange+":"+p.tradingSymbol+("MIS".equals(p.product)?"":"/"+p.product);positions.merge(key,p.netQuantity,Integer::sum);}
            if(System.currentTimeMillis()-cashAt>30000){throttle();Margin m=sdk.getMargins("equity");cash=Math.max(0,Math.min(number(m.net),number(m.available.cash)));cashAt=System.currentTimeMillis();}
            return new Snapshot(normalized,positions,cash);
        }catch(KiteException e){throw new IOException("Kite account snapshot failed ("+e.code+")");}
    }
    private static double number(String s){return s==null||s.isBlank()?0:Double.parseDouble(s);}
    public void downloadInstruments(Settings cfg)throws Exception{
        try{throttle();List<com.zerodhatech.models.Instrument> list=sdk.getInstruments("NSE");List<String> lines=new ArrayList<>();lines.add("symbol,token,tick_size,sector,as_of");Set<String> found=new HashSet<>();
            for(com.zerodhatech.models.Instrument i:list)if(cfg.symbols.contains(i.tradingsymbol)&&"EQ".equals(i.instrument_type)&&i.lot_size==1){
                found.add(i.tradingsymbol);lines.add(i.tradingsymbol+","+i.instrument_token+","+i.tick_size+",UNCLASSIFIED,"+LocalDate.now(SessionCalendar.ZONE));}
            if(found.size()!=cfg.symbols.size())throw new IOException("Some configured symbols are not eligible NSE cash equities");
            Files.createDirectories(cfg.path("instruments").getParent());Files.write(cfg.path("instruments"),lines);
        }catch(KiteException e){throw new IOException("Kite instrument download failed ("+e.code+")");}
    }
    public List<Candle> history(Equity equity,LocalDate from,LocalDate to)throws Exception{
        List<Candle> result=new ArrayList<>();
        // The SDK formats Dates using the JVM default zone. India CLI sets this explicitly before constructing SDK objects.
        if(!TimeZone.getDefault().getID().equals(SessionCalendar.ZONE.getId()))throw new IllegalStateException("SDK historical calls require India JVM timezone");
        DateTimeFormatter format=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        try{for(LocalDate day=from;!day.isAfter(to);day=day.plusDays(30)){
            LocalDate end=day.plusDays(29).isAfter(to)?to:day.plusDays(29);throttle();
            HistoricalData h=sdk.getHistoricalData(Date.from(day.atTime(9,15).atZone(SessionCalendar.ZONE).toInstant()),Date.from(end.atTime(15,29).atZone(SessionCalendar.ZONE).toInstant()),Long.toString(equity.token),"minute",false,false);
            for(HistoricalData b:h.dataArrayList){ZonedDateTime time=OffsetDateTime.parse(b.timeStamp,format).atZoneSameInstant(SessionCalendar.ZONE);result.add(new Candle(time,equity.id,b.open,b.high,b.low,b.close,b.volume));}
        }}catch(KiteException e){throw new IOException("Kite history download failed ("+e.code+")");}return result;
    }
    public void stream(Consumer<Quote> quotes,Consumer<String> health){
        Map<Long,Equity> byToken=new HashMap<>();for(Equity e:assets.values())byToken.put(e.token,e);
        ArrayList<Long> tokens=new ArrayList<>(byToken.keySet());ticker=new KiteTicker(sdk.getAccessToken(),sdk.getApiKey());
        ticker.setOnConnectedListener(()->{ticker.subscribe(tokens);ticker.setMode(tokens,KiteTicker.modeFull);health.accept("CONNECTED");});
        ticker.setOnDisconnectedListener(()->health.accept("DISCONNECTED"));
        ticker.setOnErrorListener(new OnError(){public void onError(Exception e){health.accept("FEED_ERROR");}public void onError(KiteException e){health.accept("FEED_ERROR");}public void onError(String e){health.accept("FEED_ERROR");}});
        ticker.setOnTickerArrivalListener(ticks->{for(Tick t:ticks){Equity e=byToken.get(t.getInstrumentToken());if(e==null||t.getTickTimestamp()==null)continue;
            double bid=0,ask=0;if(t.getMarketDepth()!=null){List<Depth> bids=t.getMarketDepth().get("buy"),asks=t.getMarketDepth().get("sell");if(bids!=null&&!bids.isEmpty())bid=bids.get(0).getPrice();if(asks!=null&&!asks.isEmpty())ask=asks.get(0).getPrice();}
            quotes.accept(new Quote(e.id,t.getTickTimestamp().toInstant(),t.getLastTradedPrice(),bid,ask,t.getVolumeTradedToday()));}});
        try{ticker.setTryReconnection(true);ticker.setMaximumRetries(10);ticker.setMaximumRetryInterval(30);ticker.connect();}
        catch(KiteException e){throw new IllegalStateException("Invalid feed reconnect configuration");}
    }
    @Override public void close(){if(ticker!=null){ticker.setTryReconnection(false);ticker.disconnect();}}
}
