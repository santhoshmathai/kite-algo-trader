package com.example.trading.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Strict CSV reader for validated local datasets, including escaped quotes. */
public final class Csv {
    private Csv() { }
    public static List<String[]> read(Path path, String header) throws IOException {
        List<String> lines=Files.readAllLines(path); if(lines.isEmpty() || !lines.get(0).replace("\uFEFF", "").equals(header)) throw new IOException("Unexpected CSV header: "+path);
        List<String[]> result=new ArrayList<>(); for(int i=1;i<lines.size();i++) if(!lines.get(i).isBlank()) result.add(parse(lines.get(i))); return result;
    }
    public static String[] parse(String line) throws IOException {
        List<String> fields=new ArrayList<>(); StringBuilder s=new StringBuilder(); boolean quoted=false,closed=false;
        for(int i=0;i<line.length();i++) { char c=line.charAt(i);
            if(c=='"') { if(quoted && i+1<line.length() && line.charAt(i+1)=='"') {s.append('"');i++;}
                else if(quoted) {quoted=false;closed=true;} else if(s.length()==0&&!closed) quoted=true; else throw new IOException("Malformed CSV quote"); }
            else if(c==','&&!quoted) { fields.add(s.toString()); s.setLength(0);closed=false; }
            else { if(closed) throw new IOException("Characters after CSV quote"); s.append(c); }
        }
        if(quoted) throw new IOException("Unclosed CSV quote"); fields.add(s.toString()); return fields.toArray(new String[0]);
    }
    public static String cell(Object value) { return "\""+String.valueOf(value).replace("\"","\"\"")+"\""; }
}
