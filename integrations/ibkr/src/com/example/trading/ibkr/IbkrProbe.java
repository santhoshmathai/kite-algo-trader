package com.example.trading.ibkr;

import com.ib.client.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Read-only SDK handshake. Deliberately exposes no order, cancel, position or account-update methods. */
public final class IbkrProbe {
    static final class State extends DefaultEWrapper {
        final AtomicBoolean nextIdReceived=new AtomicBoolean(), accountsReceived=new AtomicBoolean();
        final AtomicReference<String> failure=new AtomicReference<>("");
        final Set<String> accounts=ConcurrentHashMap.newKeySet();
        final Set<Integer> messageCodes=ConcurrentHashMap.newKeySet();
        volatile long serverEpoch;
        @Override public void nextValidId(int id){if(id>=0)nextIdReceived.set(true);}
        @Override public void managedAccounts(String value){
            if(value==null)return;accounts.clear();
            for(String account:value.split(","))if(!account.trim().isEmpty())accounts.add(account.trim());
            accountsReceived.set(true);
        }
        @Override public void currentTime(long epoch){serverEpoch=epoch;}
        @Override public void error(Exception e){failure.compareAndSet("","SDK transport error: "+e.getClass().getSimpleName());}
        @Override public void error(String text){failure.compareAndSet("","SDK error; inspect local TWS API log");}
        @Override public void error(int id,long errorTime,int code,String message,String advanced){
            messageCodes.add(code);
            if(Set.of(502,503,504,326,1100,1300).contains(code))failure.compareAndSet("","IBKR error code "+code);
        }
        void await(java.util.function.BooleanSupplier condition,long deadline)throws Exception{
            while(!condition.getAsBoolean()){
                if(!failure.get().isEmpty())throw new IOException(failure.get());
                if(System.nanoTime()>=deadline)throw new IOException("IBKR handshake timed out; check login, socket port and API settings");
                Thread.sleep(25);
            }
        }
    }
    public static void run(IbkrSettings cfg)throws Exception{
        if(Runtime.version().feature()<21)throw new IllegalStateException("IBKR currently requires Java 21+. Use IBKR_JAVA_HOME; India build still supports its existing JDK.");
        State state=new State();EJavaSignal signal=new EJavaSignal();EClientSocket client=new EClientSocket(state,signal);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(cfg.timeoutSeconds);
        Thread pump=null;EReader reader=null;
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(cfg.host,cfg.port),cfg.timeoutSeconds*1000);
            socket.setSoTimeout(cfg.timeoutSeconds*1000);
            try{
                // The SDK owns protocol negotiation; the supplied socket bounds connect and handshake reads.
                client.eConnect(socket,cfg.clientId);
                if(!client.isConnected())throw new IOException("SDK connection not established; check TWS/API versions and local message codes");
                socket.setSoTimeout(0);
                reader=new EReader(client,signal);reader.setDaemon(true);reader.start();
                final EReader activeReader=reader;
                pump=new Thread(()->{
                    while(client.isConnected()&&!Thread.currentThread().isInterrupted()){
                        signal.waitForSignal();
                        try{activeReader.processMsgs();}catch(IOException e){state.error(e);break;}
                    }
                },"ibkr-probe-events");pump.setDaemon(true);pump.start();
                state.await(()->state.nextIdReceived.get()&&state.accountsReceived.get(),deadline);
                if(!cfg.expectedAccount.equals("UNSET")&&!cfg.matches(state.accounts))
                    throw new IOException("Configured paper account is absent from the returned managed accounts");
                client.reqCurrentTime();
                state.await(()->state.serverEpoch>0,deadline);
                System.out.println("READ-ONLY SDK HANDSHAKE OK; serverVersion="+client.serverVersion());
                System.out.println("Server time UTC: "+Instant.ofEpochSecond(state.serverEpoch));
                System.out.println("Managed account count: "+state.accounts.size()+"; expected paper account matched: "+cfg.matches(state.accounts));
                System.out.println("IBKR message codes: "+new TreeSet<>(state.messageCodes));
                System.out.println("No orders requested. This does not validate market-data subscriptions or prove paper-mode selection.");
                if(cfg.expectedAccount.equals("UNSET"))System.out.println("Discovery only: set expectedAccount locally from the TWS paper account before the next milestone.");
            }finally{
                socket.close();client.eDisconnect();signal.issueSignal();
                if(pump!=null){pump.interrupt();pump.join(1000);}if(reader!=null){reader.interrupt();reader.join(1000);}
            }
        }
    }
}
