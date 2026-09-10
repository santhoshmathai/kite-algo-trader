package com.example.trading.ibkr;

import com.example.trading.core.*;
import com.example.trading.strategy.ORBStrategy;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline tests for the shared strategy boundary and the optional read-only IBKR adapter. */
public final class IbkrChecks {
    private static int count;
    interface Check{void run()throws Exception;}
    static void check(String name,Check c)throws Exception{c.run();count++;System.out.println("PASS "+name);}
    static void yes(boolean value){if(!value)throw new AssertionError();}
    static void fails(Check c)throws Exception{try{c.run();}catch(Exception e){return;}throw new AssertionError("Expected failure");}
    static IbkrSettings config(String replace,String value)throws Exception{
        String text=Files.readString(Path.of("config/ibkr/paper.properties"));
        text=text.replace("calendar=us-equities-2026.csv","calendar="+Path.of("config/ibkr/us-equities-2026.csv").toAbsolutePath().toString().replace('\\','/'));
        if(replace!=null)text=text.replace(replace,value);Path p=Files.createTempFile(Path.of("build"),"ibkr-check-",".properties");Files.writeString(p,text);return new IbkrSettings(p);
    }
    public static void main(String[] args)throws Exception{
        UsCalendar calendar=new UsCalendar(Path.of("config/ibkr/us-equities-2026.csv"));
        check("India defaults retain their original timezone and session",()->{
            TradingSession s=new TradingSession();yes(s.zone().equals(ZoneId.of("Asia/Kolkata"))&&s.open().equals(LocalTime.of(9,15)));
            Candle c=new Candle(ZonedDateTime.parse("2026-09-08T13:30:00Z"),"NSE:TEST",100,101,99,100,10);yes(c.getTimestamp().getHour()==19);
        });
        check("US candles retain the explicit market zone",()->{
            Candle c=new Candle(ZonedDateTime.parse("2026-09-08T13:30:00Z"),"IBKR:123",100,101,99,100,10,UsCalendar.ZONE);yes(c.getTimestamp().getHour()==9&&c.getTimestamp().getMinute()==30);
        });
        check("US holidays weekends and absent years fail closed",()->{yes(!calendar.permits(LocalDate.of(2026,9,7)));yes(!calendar.permits(LocalDate.of(2026,9,12)));fails(()->calendar.open(LocalDate.of(2027,1,4)));});
        check("US early closes are explicit",()->{yes(calendar.close(LocalDate.of(2026,11,27)).getHour()==13);yes(calendar.close(LocalDate.of(2026,12,24)).getHour()==13);});
        check("UK US daylight-saving mismatch is calculated from zones",()->{
            ZoneId uk=ZoneId.of("Europe/London");yes(calendar.open(LocalDate.of(2026,3,9)).withZoneSameInstant(uk).getHour()==13);
            yes(calendar.open(LocalDate.of(2026,3,30)).withZoneSameInstant(uk).getHour()==14);
            yes(calendar.open(LocalDate.of(2026,10,26)).withZoneSameInstant(uk).getHour()==13);
            yes(calendar.open(LocalDate.of(2026,11,2)).withZoneSameInstant(uk).getHour()==14);
        });
        check("same ORB strategy signals after complete US opening range",()->{
            LocalDate d=LocalDate.of(2026,9,8);ZonedDateTime start=calendar.open(d);
            ORBStrategy strategy=new ORBStrategy(new Instrument("IBKR:123","SMART","TEST",true),calendar.policy(d),15,LocalTime.of(10,15),.01,false);
            for(int n=0;n<15;n++){Candle c=new Candle(start.plusMinutes(n),"IBKR:123",100,101,99,100,1000,UsCalendar.ZONE);yes(strategy.onClosedCandle(c,start.plusMinutes(n+1)).isEmpty());}
            Candle c=new Candle(start.plusMinutes(15),"IBKR:123",100,102,100,102,1000,UsCalendar.ZONE);
            ORBStrategy.Signal signal=strategy.onClosedCandle(c,start.plusMinutes(16)).orElseThrow();yes(signal.side.equals("BUY")&&signal.availableAt.getHour()==9&&signal.availableAt.getMinute()==46);
        });
        check("US strategy rejects incomplete opening history",()->{
            LocalDate d=LocalDate.of(2026,9,8);ZonedDateTime start=calendar.open(d);ORBStrategy s=new ORBStrategy(new Instrument("IBKR:123","SMART","TEST",true),calendar.policy(d),15,LocalTime.of(10,15),.01,false);
            for(int n=1;n<17;n++){Candle c=new Candle(start.plusMinutes(n),"IBKR:123",100,102,99,n>=15?102:100,1000,UsCalendar.ZONE);yes(s.onClosedCandle(c,start.plusMinutes(n+1)).isEmpty());}
        });
        check("separate configuration accepts TWS and Gateway paper ports",()->{yes(config(null,null).port==7497);yes(config("port=7497","port=4002").port==4002);});
        check("live default ports and remote hosts are rejected",()->{fails(()->config("port=7497","port=7496"));fails(()->config("port=7497","port=4001"));fails(()->config("host=127.0.0.1","host=example.com"));});
        check("client zero invalid timeout and India settings are rejected",()->{fails(()->config("clientId=71","clientId=0"));fails(()->config("timeoutSeconds=15","timeoutSeconds=90"));fails(()->new IbkrSettings(Path.of("config/india.properties")));});
        check("account binding requires exact configured paper identity",()->{IbkrSettings c=config("expectedAccount=UNSET","expectedAccount=DU123456");yes(c.matches(Set.of("DU123456")));yes(!c.matches(Set.of("DU654321")));yes(!config(null,null).matches(Set.of("DU123456")));fails(()->config("expectedAccount=UNSET","expectedAccount=U123456"));});
        check("SDK callbacks collect handshake regardless of callback order",()->{IbkrProbe.State s=new IbkrProbe.State();s.managedAccounts("DU123456,DU654321");yes(!s.nextIdReceived.get());s.nextValidId(100);s.nextValidId(100);s.currentTime(1000);yes(s.accounts.size()==2&&s.accountsReceived.get()&&s.nextIdReceived.get()&&s.serverEpoch==1000);});
        check("SDK errors expose numeric codes without raw rejection content",()->{IbkrProbe.State s=new IbkrProbe.State();s.error(0,0,2104,"normal farm message","");yes(s.failure.get().isEmpty());s.error(0,0,326,"secret test payload","private JSON");yes(s.failure.get().equals("IBKR error code 326"));});
        check("handshake deadline is bounded",()->{IbkrProbe.State s=new IbkrProbe.State();fails(()->s.await(()->false,System.nanoTime()-1));});
        System.out.println("IBKR foundation checks passed: "+count);
    }
}
