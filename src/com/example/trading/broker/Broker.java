package com.example.trading.broker;

import java.time.*;
import java.util.*;

/** Broker-neutral contract. A request acknowledgement is not a fill. */
public interface Broker extends AutoCloseable {
    final class Request {
        public final String instrument, side, type, tag;
        public final int quantity;
        public final double price, trigger;
        public Request(String instrument,String side,String type,int quantity,double price,double trigger,String tag){
            if(instrument==null||!("BUY".equals(side)||"SELL".equals(side))||!Set.of("LIMIT","MARKET","SL-M").contains(type)||quantity<=0
                    ||!Double.isFinite(price)||!Double.isFinite(trigger)||price<0||trigger<0||("LIMIT".equals(type)&&price<=0)||("SL-M".equals(type)&&trigger<=0)
                    ||tag==null||!tag.matches("[A-Za-z0-9]{1,20}"))throw new IllegalArgumentException("Invalid order request");
            this.instrument=instrument;this.side=side;this.type=type;this.quantity=quantity;this.price=price;this.trigger=trigger;this.tag=tag;
        }
    }
    final class Order {
        public final String id, tag, instrument, side, type, status;
        public final int quantity, filled;
        public final double average, price, trigger;
        public Order(String id,String tag,String instrument,String side,String type,String status,int quantity,int filled,double average,double price,double trigger){
            this.id=id;this.tag=tag==null?"":tag;this.instrument=instrument;this.side=side;this.type=type;this.status=status;this.quantity=quantity;
            this.filled=filled;this.average=average;this.price=price;this.trigger=trigger;
        }
        public boolean terminal(){return Set.of("COMPLETE","REJECTED","CANCELLED").contains(status);}
    }
    final class Snapshot {
        public final List<Order> orders;
        public final Map<String,Integer> positions;
        public final double availableCash;
        public Snapshot(List<Order> orders,Map<String,Integer> positions,double availableCash){this.orders=List.copyOf(orders);this.positions=Map.copyOf(positions);this.availableCash=availableCash;}
    }
    String submit(Request request)throws Exception;
    void cancel(String orderId)throws Exception;
    void modify(String orderId,Request replacement)throws Exception;
    Snapshot snapshot()throws Exception;
    @Override default void close()throws Exception{ }
}
