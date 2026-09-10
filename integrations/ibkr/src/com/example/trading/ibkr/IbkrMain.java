package com.example.trading.ibkr;

import java.nio.file.*;
import java.time.*;

/** Separate entrypoint: the existing Kite CLI and its session lifecycle are unaffected. */
public final class IbkrMain {
    public static void main(String[] args){
        try{
            if(args.length==0||args[0].equals("--help")){
                System.out.println("IBKR: validate <connection> | schedule <connection> <date> | probe <connection>\n"
                    +"paper <connection> <US-profile> --arm-paper | stop <connection> <US-profile> | status <connection> <US-profile>\n"
                    +"summary <connection> <US-profile> <from> <to> | demo <US-profile> <new-output-directory>\n"
                    +"Paper execution only; no live-money command. Demo never connects to IBKR.");return;
            }
            if(args.length<2)throw new IllegalArgumentException("Configuration required");
            if(args[0].equals("demo")){if(args.length!=3)throw new IllegalArgumentException("demo <US-profile> <new-output-directory>");UsPaperDemo.run(new UsPaperProfile(Path.of(args[1])),Path.of(args[2]));return;}
            IbkrSettings cfg=new IbkrSettings(Path.of(args[1]));UsCalendar calendar=new UsCalendar(cfg.calendar);
            switch(args[0]){
                case "validate":if(args.length!=2)throw new IllegalArgumentException("Argument count");System.out.println("IBKR read-only configuration valid; no network access. Currency for future US trading: USD.");break;
                case "schedule":if(args.length!=3)throw new IllegalArgumentException("Date required");LocalDate day=LocalDate.parse(args[2]);
                    if(!calendar.permits(day)){System.out.println("No permitted US session on "+day+"; verify calendar coverage.");break;}
                    ZonedDateTime open=calendar.open(day);System.out.println("US open: "+open+"; London: "+open.withZoneSameInstant(ZoneId.of("Europe/London")));
                    System.out.println("US close: "+calendar.close(day));System.out.println("Paper ORB: 09:30-09:45 ET; entry cutoff 10:15 ET; flatten begins 10:30 ET.");break;
                case "probe":if(args.length!=2)throw new IllegalArgumentException("Argument count");IbkrProbe.run(cfg);break;
                case "paper":if(args.length!=4||!args[3].equals("--arm-paper"))throw new IllegalArgumentException("paper <connection> <US-profile> --arm-paper");
                    IbkrPaperRuntime.run(cfg,new UsPaperProfile(Path.of(args[2])),true);break;
                case "stop":case "status":case "summary":
                    if(args.length!=(args[0].equals("summary")?5:3))throw new IllegalArgumentException("Invalid arguments");
                    Path root=IbkrPaperRuntime.accountRoot(cfg,new UsPaperProfile(Path.of(args[2])));
                    if(args[0].equals("stop")){Files.createDirectories(root);Files.writeString(root.resolve("STOP"),"Operator requested stop\n");System.out.println("Stop requested; wait for confirmed flat in status/TWS. STOP remains until manually removed.");}
                    else if(args[0].equals("status"))System.out.println(Files.readString(root.resolve(LocalDate.now(UsCalendar.ZONE).toString()).resolve("status.txt")));
                    else UsPaperReports.summary(root,LocalDate.parse(args[3]),LocalDate.parse(args[4]));break;
                default:throw new IllegalArgumentException("Unknown command; use --help (paper execution only)");
            }
        }catch(Exception e){System.err.println("IBKR command failed: "+e.getMessage());System.exit(2);}
        catch(LinkageError e){System.err.println("IBKR SDK/runtime classpath mismatch. Use the documented official SDK, bundled protobuf and supported JDK.");System.exit(2);}
    }
}
