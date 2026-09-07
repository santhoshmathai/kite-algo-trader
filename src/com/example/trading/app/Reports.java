package com.example.trading.app;

import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Small local audit report, written without external libraries or a running web server. */
public final class Reports {
    private Reports(){ }
    public static void write(Path directory,TradingEngine engine,String mode,String context)throws IOException{
        Files.createDirectories(directory);List<String> rows=new ArrayList<>();rows.add("instrument,side,entry_quantity,entry_average,remaining,gross_pnl,estimated_costs,net_pnl,exit_reason");
        StringBuilder table=new StringBuilder();
        List<String> orders=new ArrayList<>();orders.add("tag,order_id,instrument,role,side,type,requested_quantity,filled_quantity,average_fill_price,limit_price,stop_trigger,status,pending_action");
        for(TradingEngine.Trade t:engine.trades()){
            List<String> tags=new ArrayList<>();tags.add(t.entryTag);tags.addAll(t.exits);
            for(String tag:tags){TradingEngine.Tracked o=engine.tracked(tag);
                orders.add(String.join(",",Csv.cell(tag),Csv.cell(o.brokerId),Csv.cell(t.instrument),tag.equals(t.entryTag)?"ENTRY":"EXIT",
                        o.request.side,o.request.type,""+o.request.quantity,""+o.filled,""+o.average,""+o.request.price,""+o.request.trigger,Csv.cell(o.status),Csv.cell(o.pending)));}
            TradingEngine.Tracked e=engine.tracked(t.entryTag);double gross=engine.tradeGross(t),cost=engine.tradeCosts(t);
            rows.add(Csv.cell(t.instrument)+","+t.side+","+e.filled+","+e.average+","+engine.remaining(t)+","+gross+","+cost+","+(gross-cost)+","+Csv.cell(t.reason));
            table.append("<tr><td>").append(html(t.instrument)).append("</td><td>").append(t.side).append("</td><td>").append(e.filled).append("</td><td>").append(engine.remaining(t)).append("</td><td>").append(String.format(Locale.ROOT,"%.2f",gross-cost)).append("</td><td>").append(html(t.reason)).append("</td></tr>");
        }
        Files.write(directory.resolve("trades.csv"),rows);Files.write(directory.resolve("orders.csv"),orders);Files.write(directory.resolve("decisions.log"),engine.messages());
        String report="<!doctype html><html lang='en'><meta charset='utf-8'><title>Kite Algo Trader report</title><style>body{font:16px system-ui;margin:40px;max-width:1100px;background:#f6f8fc;color:#17233a}table{border-collapse:collapse;width:100%;background:white}td,th{padding:12px;text-align:left;border-bottom:1px solid #ddd}code{overflow-wrap:anywhere}</style><h1>Kite Algo Trader</h1><p>"+html(mode)+" · "+engine.date+" · "+engine.modeStatus()+"</p><h2>Net marked P&amp;L: INR "+String.format(Locale.ROOT,"%.2f",engine.netPnl())+"</h2><p>"+html(engine.reason())+"</p><p>Confirmed flat: "+engine.flat()+". Fees are estimates; synthetic/paper results do not establish profitability.</p><table><tr><th>Instrument</th><th>Side</th><th>Filled</th><th>Remaining</th><th>Net INR</th><th>Exit</th></tr>"+table+"</table><p>Run context: <code>"+html(context)+"</code></p></html>";
        Path temporary=directory.resolve("report.html.tmp");Files.writeString(temporary,report);Files.move(temporary,directory.resolve("report.html"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(directory.resolve("status.txt"),engine.modeStatus()+"\n"+engine.reason()+"\nflat="+engine.flat()+"\nnetPnl="+engine.netPnl()+"\nreportWrittenAt="+java.time.Instant.now()+"\n");
        Properties metadata=new Properties();metadata.setProperty("schema","1");metadata.setProperty("mode",mode);
        metadata.setProperty("date",engine.date.toString());metadata.setProperty("capital",""+engine.config.capital);
        metadata.setProperty("configFingerprint",engine.config.fingerprint());metadata.setProperty("context",context);
        metadata.setProperty("state",engine.modeStatus());metadata.setProperty("flat",""+engine.flat());metadata.setProperty("netPnl",""+engine.netPnl());
        metadata.setProperty("reason",engine.reason());metadata.setProperty("reportWrittenAt",java.time.Instant.now().toString());
        Path metadataTemp=directory.resolve("session.properties.tmp");
        try(Writer writer=Files.newBufferedWriter(metadataTemp)){metadata.store(writer,"Report metadata; no broker credentials");}
        try{Files.move(metadataTemp,directory.resolve("session.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException ex){Files.move(metadataTemp,directory.resolve("session.properties"),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
