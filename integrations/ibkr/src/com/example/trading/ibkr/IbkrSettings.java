package com.example.trading.ibkr;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A separate configuration namespace: India settings cannot be mistaken for IBKR settings. */
public final class IbkrSettings {
    public final String host, expectedAccount;
    public final int port, clientId, timeoutSeconds;
    public final Path calendar;
    public IbkrSettings(Path file) throws IOException {
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}
        Set<String> keys=Set.of("host","port","clientId","timeoutSeconds","expectedAccount","calendar");
        for(String key:p.stringPropertyNames())if(!keys.contains(key))throw new IllegalArgumentException("Unknown IBKR setting: "+key);
        host=p.getProperty("host","127.0.0.1");
        if(!host.equals("127.0.0.1"))throw new IllegalArgumentException("Only IPv4 loopback is supported initially");
        port=Integer.parseInt(p.getProperty("port","7497"));
        if(port!=7497&&port!=4002)throw new IllegalArgumentException("Only configured paper default ports 7497/4002 are allowed");
        clientId=Integer.parseInt(p.getProperty("clientId","71"));
        timeoutSeconds=Integer.parseInt(p.getProperty("timeoutSeconds","15"));
        if(clientId<1||clientId>999999||timeoutSeconds<2||timeoutSeconds>30)throw new IllegalArgumentException("Invalid client ID or timeout");
        expectedAccount=p.getProperty("expectedAccount","UNSET");
        if(!expectedAccount.equals("UNSET")&&!expectedAccount.matches("DU[0-9]+"))throw new IllegalArgumentException("Use an explicit DU paper account or UNSET for discovery");
        if(!p.containsKey("calendar"))throw new IllegalArgumentException("US calendar required");
        calendar=file.toAbsolutePath().getParent().resolve(p.getProperty("calendar")).normalize();
    }
    /** Prefix/port are guardrails, not independent proof that a broker session is paper. */
    public boolean matches(Set<String> accounts){return !expectedAccount.equals("UNSET")&&accounts.contains(expectedAccount);}
}
