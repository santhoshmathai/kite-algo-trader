package com.example.trading.ibkr;

import com.example.trading.app.*;
import java.nio.file.*;
import java.time.*;

/** USD-only, long-only experiment. No live-money execution switch exists. */
public final class UsPaperProfile {
    public final Settings risk;
    public UsPaperProfile(Path file)throws Exception{
        risk=new Settings(file);
        if(risk.shorts||risk.liveEnabled||!risk.cutoff.equals(LocalTime.of(10,15))||!risk.flatten.equals(LocalTime.of(10,30)))
            throw new IllegalArgumentException("US paper requires shorts=false, liveEnabled=false, entryCutoff=10:15, flattenAt=10:30");
        if(!risk.values.getProperty("fees","").equals("USD_ESTIMATE"))throw new IllegalArgumentException("US profile requires fees=USD_ESTIMATE; amounts are USD");
    }
    // Deliberately conservative allowance, not an IBKR fee schedule. Actual statements may differ.
    public static final TradeCosts COSTS=(date,side,quantity,price)->quantity==0?0:Math.max(1,quantity*.01);
}
