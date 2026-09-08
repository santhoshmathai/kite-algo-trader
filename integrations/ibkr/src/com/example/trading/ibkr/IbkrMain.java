package com.example.trading.ibkr;

import java.nio.file.*;
import java.time.*;

/** Separate entrypoint: the existing Kite CLI and its session lifecycle are unaffected. */
public final class IbkrMain {
    public static void main(String[] args){
        try{
            if(args.length==0||args[0].equals("--help")){
                System.out.println("IBKR foundation: validate <config> | schedule <config> <YYYY-MM-DD> | probe <config>\nRead-only milestone. Automated IBKR paper/live trading is not implemented yet.");return;
            }
            if(args.length<2)throw new IllegalArgumentException("Configuration required");
            IbkrSettings cfg=new IbkrSettings(Path.of(args[1]));UsCalendar calendar=new UsCalendar(cfg.calendar);
            switch(args[0]){
                case "validate":if(args.length!=2)throw new IllegalArgumentException("Argument count");System.out.println("IBKR read-only configuration valid; no network access. Currency for future US trading: USD.");break;
                case "schedule":if(args.length!=3)throw new IllegalArgumentException("Date required");LocalDate day=LocalDate.parse(args[2]);
                    if(!calendar.permits(day)){System.out.println("No permitted US session on "+day+"; verify calendar coverage.");break;}
                    ZonedDateTime open=calendar.open(day);System.out.println("US open: "+open+"; London: "+open.withZoneSameInstant(ZoneId.of("Europe/London")));
                    System.out.println("US close: "+calendar.close(day));System.out.println("Proposed ORB: 09:30-09:45 ET; proposed entry cutoff 10:15 ET; flatten begins 10:30 ET (not active trading commands).");break;
                case "probe":if(args.length!=2)throw new IllegalArgumentException("Argument count");IbkrProbe.run(cfg);break;
                default:throw new IllegalArgumentException("Unknown command; no IBKR trading command is enabled");
            }
        }catch(Exception e){System.err.println("IBKR command failed: "+e.getMessage());System.exit(2);}
        catch(LinkageError e){System.err.println("IBKR SDK/runtime classpath mismatch. Use the documented official SDK, bundled protobuf and supported JDK.");System.exit(2);}
    }
}
