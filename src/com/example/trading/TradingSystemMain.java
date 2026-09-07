package com.example.trading;

import com.example.trading.app.*;
import com.example.trading.broker.*;
import com.example.trading.core.Candle;
import com.example.trading.replay.Backtest;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local command line application. Network access and execution are explicit commands. */
public final class TradingSystemMain {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(SessionCalendar.ZONE));
        try { execute(args); }
        catch(Exception e) {
            // SDK transport errors may contain credential-bearing URLs: do not dump exception messages or stacks.
            System.err.println("Command failed ("+e.getClass().getSimpleName()+"). Check command arguments, configuration, data and daily session. No automatic command retry.");
            System.exit(2);
        }
    }
    public static void execute(String[] args)throws Exception {
        if(args.length==0||args[0].equals("--help")){help();return;}
        String command=args[0];
        if(command.equals("--demo")){require(args,1);FoundationDemo.main(args);return;}
        if(command.equals("login-url")){require(args,1);System.out.println(KiteBroker.loginUrl());return;}
        if(command.equals("status")){require(args,2);System.out.print(Files.readString(Path.of(args[1]).resolve("status.txt")));return;}
        if(command.equals("stop")){require(args,2);Path account=Path.of(args[1]);if(!Files.exists(account.resolve("account.lock")))throw new IllegalArgumentException("Not a session account directory");Files.writeString(account.resolve("STOP"),"Operator stop requested\n");System.out.println("Stop requested. Wait for confirmed flat status and verify Kite positions.");return;}
        if(args.length<2)throw new IllegalArgumentException("Configuration path required");
        Settings cfg=new Settings(Path.of(args[1]));
        switch(command){
            case "login":require(args,2);KiteBroker.login(cfg);break;
            case "instruments":require(args,2);try(KiteBroker kite=new KiteBroker(cfg,Map.of(),false)){kite.downloadInstruments(cfg);}System.out.println("Instrument mappings saved.");break;
            case "validate":require(args,2);Map<String,Equity> a=Equity.read(cfg.path("instruments"),cfg.symbols);new SessionCalendar(cfg.path("calendar"));new Fees(cfg.path("fees"));System.out.println("Configuration and "+a.size()+" equity mappings valid. Live enabled: "+cfg.liveEnabled);break;
            case "backtest":require(args,4);System.out.println("Report: "+Backtest.run(cfg,Path.of(args[2]),Path.of(args[3])));break;
            case "paper":require(args,2);TradingRuntime.run(cfg,false,false);break;
            case "live":require(args,3);if(!args[2].equals("--arm-live"))throw new IllegalArgumentException("Explicit live arm required");TradingRuntime.run(cfg,true,true);break;
            case "download":require(args,5);download(cfg,LocalDate.parse(args[2]),LocalDate.parse(args[3]),Path.of(args[4]));break;
            default:throw new IllegalArgumentException("Unknown command");
        }
    }
    private static void download(Settings cfg,LocalDate from,LocalDate to,Path output)throws Exception{
        if(to.isBefore(from)||to.isAfter(LocalDate.now(SessionCalendar.ZONE))||from.plusYears(5).isBefore(to))throw new IllegalArgumentException("Invalid date range");
        Map<String,Equity> assets=Equity.read(cfg.path("instruments"),cfg.symbols);List<Candle> bars=new ArrayList<>();
        try(KiteBroker kite=new KiteBroker(cfg,assets,false)){for(Equity e:assets.values())bars.addAll(kite.history(e,from,to));}
        bars.sort(Comparator.comparing(Candle::getTimestamp).thenComparing(Candle::getInstrumentToken));
        List<String> lines=new ArrayList<>();lines.add("timestamp,instrument,open,high,low,close,volume");
        for(Candle c:bars)lines.add(c.getTimestamp().toOffsetDateTime()+","+c.getInstrumentToken()+","+c.getOpen()+","+c.getHigh()+","+c.getLow()+","+c.getClose()+","+c.getVolume());
        Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,lines,StandardOpenOption.CREATE_NEW);System.out.println("Saved "+bars.size()+" bars. Validate calendar and historical fee coverage before replay.");
    }
    private static void require(String[] args,int n){if(args.length!=n)throw new IllegalArgumentException("Wrong argument count; see --help");}
    private static void help(){System.out.println("Kite Algo Trader | India NSE equities | local standalone application\n"
        +"java -jar build/kite-algo-trader.jar <command>\n"
        +"  --demo | --help\n  validate <config>\n  login-url\n  login <config>\n  instruments <config>\n"
        +"  download <config> <from YYYY-MM-DD> <to YYYY-MM-DD> <new bars.csv>\n"
        +"  backtest <config> <bars.csv> <output directory>\n  paper <config>\n  live <config> --arm-live\n"
        +"  status <day output directory>\n  stop <account output directory>\n"
        +"Live is disabled by default. See docs/OPERATIONS.md for login, static IP, recovery and data requirements.");}
}
