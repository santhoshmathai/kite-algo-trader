package com.example.trading.storage;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Single-writer, checksummed, forced-to-disk snapshots. No native Java object deserialization. */
public final class Journal implements AutoCloseable {
    private static final int MAX=4*1024*1024;
    private final FileChannel channel;
    private final FileLock lock;
    private Properties latest=new Properties();
    public Journal(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        FileLock acquired=null;
        try { acquired=channel.tryLock(); if(acquired==null) throw new IOException("Account state is already locked"); scan(); }
        catch(Exception e) { if(acquired!=null)acquired.release();channel.close();throw new IOException("Cannot open journal: "+e.getMessage(),e); }
        lock=acquired;
    }
    private void scan() throws IOException {
        long valid=0; channel.position(0);
        while(channel.position()<channel.size()) {
            ByteBuffer header=ByteBuffer.allocate(12);
            if(!readFully(header)) {channel.truncate(valid);channel.force(true);break;}
            header.flip();int length=header.getInt();long checksum=header.getLong();
            if(length<1||length>MAX)throw new IOException("Corrupt journal record length");
            ByteBuffer body=ByteBuffer.allocate(length);
            if(!readFully(body)){channel.truncate(valid);channel.force(true);break;}
            CRC32 crc=new CRC32();crc.update(body.array());if(crc.getValue()!=checksum)throw new IOException("Journal checksum failure; recovery required");
            Properties state=new Properties();state.load(new ByteArrayInputStream(body.array()));
            if(!"1".equals(state.getProperty("schema")))throw new IOException("Unsupported journal schema");
            latest=state;valid=channel.position();
        }
        channel.position(channel.size());
    }
    private boolean readFully(ByteBuffer b)throws IOException{while(b.hasRemaining()){if(channel.read(b)==-1)return false;}return true;}
    public synchronized Properties latest(){Properties p=new Properties();p.putAll(latest);return p;}
    public synchronized void save(Properties state)throws IOException{
        Properties copy=new Properties();copy.putAll(state);copy.setProperty("schema","1");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy.store(bytes,"Kite Algo Trader state");byte[] payload=bytes.toByteArray();
        if(payload.length>MAX)throw new IOException("Journal state too large");
        CRC32 crc=new CRC32();crc.update(payload);ByteBuffer record=ByteBuffer.allocate(12+payload.length);
        record.putInt(payload.length).putLong(crc.getValue()).put(payload).flip();
        while(record.hasRemaining())channel.write(record);channel.force(true);latest=copy;
    }
    @Override public synchronized void close()throws IOException{lock.release();channel.close();}
}
