package com.example.trading;

import com.example.trading.core.*;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.order.OrderManager;
import com.example.trading.risk.*;
import com.example.trading.strategy.ORBStrategy;
import java.time.*;
import java.util.*;

/** Dependency-free regression suite, also called by the JUnit adapter under Maven. */
public final class FoundationChecks {
    private static int passed;
    private static final ZonedDateTime OPEN = ZonedDateTime.of(2026, 9, 7, 9, 15, 0, 0, TradingSession.ZONE);
    private static final Instrument STOCK = new Instrument("123", "NSE", "TEST", true);
    private static void check(String name, Runnable test) {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static void eq(double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-7)
            throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void truth(boolean value) { if (!value) throw new AssertionError("Condition failed"); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static TickData tick(ZonedDateTime time, double price, long total) {
        return new TickData(time, STOCK.token, price, 999, total, price, null);
    }
    private static Candle bar(ZonedDateTime time, double high, double low, double close) {
        return new Candle(time, STOCK.token, low, high, low, close, 10);
    }
    private static InstrumentRegistry registry() {
        InstrumentRegistry r = new InstrumentRegistry(); r.register(STOCK); return r;
    }
    private static OrderManager orders() { return new OrderManager(registry()); }
    private static String order(OrderManager o, String side, int qty, double price) {
        return o.placeOrder(Map.of("exchange", "NSE", "tradingsymbol", "TEST", "transaction_type", side,
                "quantity", qty, "price", price, "product", "MIS", "order_type", "LIMIT"), "regular");
    }
    private static void fill(OrderManager o, String side, int qty, double price) {
        o.updateOrderStatus(order(o, side, qty, price), "COMPLETE", qty, price);
    }
    private static ORBStrategy strategy(boolean shorts) {
        return new ORBStrategy(STOCK, new TradingSession(), 15, LocalTime.of(10, 0), 0.05, shorts);
    }
    private static void opening(ORBStrategy s, ZonedDateTime day, double high, double low) {
        for (int i = 0; i < 15; i++) s.onClosedCandle(bar(day.plusMinutes(i), high, low, low), day.plusMinutes(i + 1));
    }
    public static void main(String[] args) { runAll(); }
    public static void runAll() {
        passed = 0;
        check("15 minute OHLCV and three five-minute bars", () -> {
            TimeSeriesManager t = new TimeSeriesManager();
            for (int i = 0; i < 15; i++) t.addTick(tick(OPEN.plusMinutes(i), 100 + i, (i + 1) * 10));
            eq(14, t.getOneMinSeries(STOCK.token).size());
            eq(0, t.getFifteenMinSeries(STOCK.token).size());
            t.advanceTime(OPEN.plusMinutes(15));
            eq(15, t.getOneMinSeries(STOCK.token).size()); eq(3, t.getFiveMinSeries(STOCK.token).size());
            eq(1, t.getFifteenMinSeries(STOCK.token).size());
            Candle c = t.getFifteenMinSeries(STOCK.token).getLast();
            truth(c.getTimestamp().equals(OPEN)); eq(100, c.getOpen()); eq(114, c.getHigh());
            eq(100, c.getLow()); eq(114, c.getClose()); eq(150, c.getVolume());
        });
        check("snapshot volume deltas and duplicate snapshots", () -> {
            TimeSeriesManager t = new TimeSeriesManager();
            t.addTick(tick(OPEN, 100, 10)); t.addTick(tick(OPEN.plusSeconds(1), 102, 15));
            t.addTick(tick(OPEN.plusSeconds(1), 102, 15)); t.advanceTime(OPEN.plusMinutes(1));
            Candle c = t.getOneMinSeries(STOCK.token).getLast(); eq(15, c.getVolume()); eq(102, c.getClose());
        });
        check("UTC timestamps normalize to India session", () -> {
            TimeSeriesManager t = new TimeSeriesManager();
            t.addTick(tick(OPEN.withZoneSameInstant(ZoneId.of("UTC")), 100, 10)); t.advanceTime(OPEN.plusMinutes(1));
            truth(t.getOneMinSeries(STOCK.token).getLast().getTimestamp().equals(OPEN));
        });
        check("late ticks rejected without rewriting closed bars", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); t.addTick(tick(OPEN, 100, 10));
            t.advanceTime(OPEN.plusMinutes(1)); rejects(() -> t.addTick(tick(OPEN.plusSeconds(30), 90, 20)));
            eq(100, t.getOneMinSeries(STOCK.token).getLast().getClose());
        });
        check("same-session cumulative reset rejected", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); t.addTick(tick(OPEN, 100, 10));
            rejects(() -> t.addTick(tick(OPEN.plusSeconds(1), 99, 9)));
            t.advanceTime(OPEN.plusMinutes(1)); eq(10, t.getOneMinSeries(STOCK.token).getLast().getVolume());
        });
        check("new session resets cumulative volume", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); t.addTick(tick(OPEN, 100, 100));
            t.addTick(tick(OPEN.plusDays(1), 90, 10)); t.advanceTime(OPEN.plusDays(1).plusMinutes(1));
            eq(2, t.getOneMinSeries(STOCK.token).size()); eq(10, t.getOneMinSeries(STOCK.token).getLast().getVolume());
        });
        check("late startup minute omitted including later ticks in same minute", () -> {
            TimeSeriesManager t = new TimeSeriesManager();
            t.addTick(tick(OPEN.plusSeconds(10), 100, 100)); t.addTick(tick(OPEN.plusSeconds(20), 101, 110));
            t.advanceTime(OPEN.plusMinutes(1)); eq(0, t.getOneMinSeries(STOCK.token).size());
            t.addTick(tick(OPEN.plusMinutes(1), 102, 120)); t.advanceTime(OPEN.plusMinutes(2));
            eq(1, t.getOneMinSeries(STOCK.token).size()); eq(10, t.getOneMinSeries(STOCK.token).getLast().getVolume());
        });
        check("gap minute does not attribute missing cumulative volume to a complete bar", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); t.addTick(tick(OPEN, 100, 10));
            t.addTick(tick(OPEN.plusMinutes(3), 103, 40)); t.addTick(tick(OPEN.plusMinutes(3).plusSeconds(1), 104, 50));
            t.advanceTime(OPEN.plusMinutes(4)); eq(1, t.getOneMinSeries(STOCK.token).size());
        });
        check("missing historical minute prevents higher bar", () -> {
            TimeSeriesManager t = new TimeSeriesManager();
            for (int i = 0; i < 15; i++) if (i != 7) t.addClosedCandle(bar(OPEN.plusMinutes(i), 101, 99, 100));
            eq(0, t.getFifteenMinSeries(STOCK.token).size()); eq(2, t.getFiveMinSeries(STOCK.token).size());
        });
        check("historical and live aggregation agree", () -> {
            TimeSeriesManager history = new TimeSeriesManager(), live = new TimeSeriesManager();
            for (int i = 0; i < 15; i++) {
                history.addClosedCandle(new Candle(OPEN.plusMinutes(i), STOCK.token, 100+i, 100+i, 100+i, 100+i, 10));
                live.addTick(tick(OPEN.plusMinutes(i), 100+i, 10*(i+1)));
            }
            live.advanceTime(OPEN.plusMinutes(15));
            truth(history.getFifteenMinSeries(STOCK.token).getLast().toString().equals(live.getFifteenMinSeries(STOCK.token).getLast().toString()));
        });
        check("historical duplicates and overlaps rejected", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); Candle c = bar(OPEN, 101, 99, 100);
            t.loadHistoricalCandles(STOCK.token, List.of(c), 1);
            rejects(() -> t.addClosedCandle(c)); rejects(() -> t.loadHistoricalCandles(STOCK.token, List.of(c), 1));
            rejects(() -> t.addTick(tick(OPEN.plusMinutes(1), 100, 20)));
        });
        check("history validation is atomic", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); Candle c = bar(OPEN, 101, 99, 100);
            rejects(() -> t.loadHistoricalCandles(STOCK.token, List.of(c, c), 1)); eq(0, t.getOneMinSeries(STOCK.token).size());
        });
        check("weekends holidays and session boundaries", () -> {
            TradingSession s = new TradingSession(Set.of(OPEN.toLocalDate().plusDays(1)));
            truth(s.contains(OPEN)); truth(!s.contains(OPEN.minusSeconds(1)));
            truth(!s.contains(OPEN.withHour(15).withMinute(30))); truth(!s.contains(OPEN.minusDays(1)));
            truth(!s.contains(OPEN.plusDays(1)));
        });
        check("immutable data snapshots", () -> {
            TimeSeriesManager t = new TimeSeriesManager(); t.addClosedCandle(bar(OPEN, 101, 99, 100));
            t.getOneMinSeries(STOCK.token).clear(); eq(1, t.getOneMinSeries(STOCK.token).size());
        });
        check("invalid OHLCV tick and candle alignment rejected", () -> {
            rejects(() -> new Candle(OPEN, "123", 100, 99, 90, 100, 1));
            rejects(() -> new Candle(OPEN, "123", 100, 101, 99, Double.NaN, 1));
            rejects(() -> tick(OPEN, Double.NaN, 1));
            rejects(() -> new TimeSeriesManager().addClosedCandle(bar(OPEN.plusSeconds(1), 101, 99, 100)));
        });
        check("short roundtrip regression: profit positive 100", () -> {
            OrderManager o = orders(); fill(o, "SELL", 10, 100); eq(100, o.getPosition("123").averageSellPrice);
            fill(o, "BUY", 10, 90); eq(100, o.getPosition("123").realizedPnl); eq(0, o.getAllOpenPositions().size());
        });
        check("long partial close retains entry basis", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); fill(o, "SELL", 4, 110);
            eq(6, o.getPosition("123").netQuantity); eq(100, o.getPosition("123").averageBuyPrice);
            eq(40, o.getPosition("123").realizedPnl); fill(o, "SELL", 6, 90); eq(-20, o.getTotalPnl());
        });
        check("partial fills account immediately and cancel preserves exposure", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            o.updateOrderStatus(id, "OPEN", 4, 100); eq(4, o.getPosition("123").netQuantity);
            o.updateOrderStatus(id, "CANCELLED", 4, 100); eq(4, o.getPosition("123").netQuantity); eq(0, o.getAllActiveOrders().size());
        });
        check("cancel notification carrying first fill applies it", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            o.updateOrderStatus(id, "CANCELLED", 4, 100); eq(4, o.getPosition("123").netQuantity);
        });
        check("duplicate and stale cumulative fill snapshots are idempotent", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            o.updateOrderStatus(id, "OPEN", 5, 100); o.updateOrderStatus(id, "OPEN", 5, 100);
            o.updateOrderStatus(id, "OPEN", 3, 99); eq(5, o.getPosition("123").netQuantity);
            o.updateOrderStatus(id, "COMPLETE", 10, 102); o.updateOrderStatus(id, "COMPLETE", 10, 102);
            eq(10, o.getPosition("123").netQuantity); eq(102, o.getPosition("123").averageBuyPrice);
        });
        check("cumulative average converted to incremental fill value", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            o.updateOrderStatus(id, "OPEN", 5, 100); fill(o, "SELL", 5, 110);
            o.updateOrderStatus(id, "COMPLETE", 10, 102);
            eq(5, o.getPosition("123").netQuantity); eq(104, o.getPosition("123").averageBuyPrice);
            eq(50, o.getPosition("123").realizedPnl);
        });
        check("position flips set new side basis", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); fill(o, "SELL", 15, 110);
            eq(-5, o.getPosition("123").netQuantity); eq(110, o.getPosition("123").averageSellPrice);
            eq(100, o.getPosition("123").realizedPnl); fill(o, "BUY", 8, 100);
            eq(3, o.getPosition("123").netQuantity); eq(100, o.getPosition("123").averageBuyPrice);
            eq(150, o.getPosition("123").realizedPnl);
        });
        check("terminal order never reopens on delayed open", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            o.updateOrderStatus(id, "CANCELLED", 3, 100); o.updateOrderStatus(id, "OPEN", 3, 100);
            eq(0, o.getAllActiveOrders().size()); o.updateOrderStatus(id, "COMPLETE", 10, 100);
            eq(10, o.getPosition("123").netQuantity); eq(0, o.getAllActiveOrders().size());
        });
        check("invalid fill updates do not mutate ledger", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100);
            rejects(() -> o.updateOrderStatus(id, "COMPLETE", 11, 100));
            rejects(() -> o.updateOrderStatus(id, "COMPLETE", 5, 100));
            rejects(() -> o.updateOrderStatus(id, "OPEN", 5, Double.NaN));
            rejects(() -> o.updateOrderStatus("missing", "OPEN", 1, 100));
            truth(o.getPosition("123") == null);
        });
        check("mark-to-market includes realized results after flat", () -> {
            OrderManager o = orders(); fill(o, "SELL", 10, 100); o.markPrice("123", 90); eq(100, o.getTotalPnl());
            fill(o, "BUY", 10, 90); eq(100, o.getTotalPnl());
            fill(o, "BUY", 5, 80); o.markPrice("123", 82); eq(110, o.getTotalPnl());
        });
        check("snapshot mutation cannot alter ledger", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100); o.updateOrderStatus(id, "COMPLETE", 10, 100);
            o.getPosition("123").netQuantity = 999; o.getOrderDetails(id).status = "OPEN";
            eq(10, o.getPosition("123").netQuantity); eq(0, o.getAllActiveOrders().size());
        });
        check("unique order IDs at high submission speed", () -> {
            OrderManager o = orders(); Set<String> ids = new HashSet<>();
            for (int i=0; i<1000; i++) ids.add(order(o, "BUY", 1, 100)); eq(1000, ids.size());
        });
        check("symbol maps to token and index cannot be ordered", () -> {
            InstrumentRegistry r = registry(); r.register(new Instrument("456", "NSE", "INDEX", false));
            OrderManager o = new OrderManager(r); String id = order(o, "BUY", 1, 100);
            truth(o.getOrderDetails(id).instrumentToken.equals("123"));
            rejects(() -> r.forOrder("NSE", "INDEX")); rejects(() -> r.register(STOCK));
            rejects(() -> r.forOrder("NSE", "123"));
        });
        check("exchange-qualified identical symbols remain distinct", () -> {
            InstrumentRegistry r = registry(); r.register(new Instrument("456", "BSE", "TEST", true));
            truth(r.forOrder("NSE", "TEST").token.equals("123")); truth(r.forOrder("BSE", "TEST").token.equals("456"));
        });
        check("risk stop generates one exit using correct symbol", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); RiskManager r = new RiskManager(o, 100000, 1000);
            r.setStopLoss("123", 95); r.setTakeProfit("123", 110);
            r.checkPositions("123", 94); r.checkPositions("123", 94); eq(1, o.getAllActiveOrders().size());
            OrderManager.OrderDetails exit = o.getAllActiveOrders().get(0);
            truth(exit.tradingSymbol.equals("TEST")); truth(exit.instrumentToken.equals("123")); truth(exit.transactionType.equals("SELL"));
            o.updateOrderStatus(exit.orderId, "COMPLETE", 10, 94); r.checkPositions("123", 94); eq(0, o.getAllActiveOrders().size());
        });
        check("rejected exit preserves protection and retries remaining position", () -> {
            OrderManager o = orders(); fill(o, "SELL", 10, 100); RiskManager r = new RiskManager(o, 100000, 1000);
            r.setStopLoss("123", 105); r.checkPositions("123", 106);
            String id = o.getAllActiveOrders().get(0).orderId; o.updateOrderStatus(id, "REJECTED", 0, 0);
            r.checkPositions("123", 106); eq(1, o.getAllActiveOrders().size());
            truth(!id.equals(o.getAllActiveOrders().get(0).orderId));
        });
        check("partial exit not duplicated and remaining close correctly sized", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); String id = o.requestExit("123");
            o.updateOrderStatus(id, "OPEN", 4, 95); truth(id.equals(o.requestExit("123")));
            o.updateOrderStatus(id, "CANCELLED", 4, 95); String retry = o.requestExit("123");
            eq(6, o.getOrderDetails(retry).quantity);
        });
        check("loss limit halts entries and tracks actual equity", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); RiskManager r = new RiskManager(o, 100000, 50);
            r.checkPositions("123", 94); r.checkDrawdown(); truth(r.isHalted()); eq(99940, r.getEquity());
            rejects(() -> order(o, "BUY", 1, 100)); eq(1, o.getAllActiveOrders().size());
        });
        check("flatten cancels pending entry before closing filled portion", () -> {
            OrderManager o = orders(); String id = order(o, "BUY", 10, 100); o.updateOrderStatus(id, "OPEN", 3, 100);
            new RiskManager(o, 100000, 1000).flattenAll();
            truth(o.getOrderDetails(id).status.equals("CANCELLED")); eq(3, o.getAllActiveOrders().get(0).quantity);
        });
        check("opening range uses all fifteen completed bars", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99); truth(s.isOpeningRangeEstablished());
            eq(101, s.getOpeningRangeHigh()); eq(99, s.getOpeningRangeLow());
            ORBStrategy.Signal signal = s.onClosedCandle(bar(OPEN.plusMinutes(15), 102, 100, 102), OPEN.plusMinutes(16)).orElseThrow();
            truth(signal.side.equals("BUY")); eq(99, signal.stopPrice); truth(signal.availableAt.equals(OPEN.plusMinutes(16)));
        });
        check("forming candle cannot signal", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99);
            rejects(() -> s.onClosedCandle(bar(OPEN.plusMinutes(15), 102, 100, 102), OPEN.plusMinutes(15).plusSeconds(59)));
        });
        check("missing opening minute disables the day", () -> {
            ORBStrategy s = strategy(false);
            for (int i=0; i<15; i++) if(i!=7) s.onClosedCandle(bar(OPEN.plusMinutes(i),101,99,100), OPEN.plusMinutes(i+1));
            truth(!s.isOpeningRangeEstablished());
            truth(s.onClosedCandle(bar(OPEN.plusMinutes(15), 102, 100, 102), OPEN.plusMinutes(16)).isEmpty());
        });
        check("daily ORB reset excludes yesterday range", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 200, 190);
            opening(s, OPEN.plusDays(1), 101, 99); eq(101, s.getOpeningRangeHigh());
            truth(s.onClosedCandle(bar(OPEN.plusDays(1).plusMinutes(15), 102, 100, 102), OPEN.plusDays(1).plusMinutes(16)).isPresent());
        });
        check("entry cutoff uses close time, excludes exactly 10:00", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99);
            truth(s.onClosedCandle(bar(OPEN.plusMinutes(44), 102, 100, 102), OPEN.plusMinutes(45)).isEmpty());
        });
        check("stale candle cannot create a late entry", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99);
            truth(s.onClosedCandle(bar(OPEN.plusMinutes(15), 102, 100, 102), OPEN.plusMinutes(17)).isEmpty());
        });
        check("one signal per session including repeated callback", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99); Candle c = bar(OPEN.plusMinutes(15), 102, 100, 102);
            truth(s.onClosedCandle(c, OPEN.plusMinutes(16)).isPresent()); truth(s.onClosedCandle(c, OPEN.plusMinutes(16)).isEmpty());
            truth(s.onClosedCandle(bar(OPEN.plusMinutes(16), 103, 102, 103), OPEN.plusMinutes(17)).isEmpty());
        });
        check("short side explicit opt-in", () -> {
            ORBStrategy s = strategy(true); opening(s, OPEN, 101, 99);
            ORBStrategy.Signal signal = s.onClosedCandle(bar(OPEN.plusMinutes(15), 99, 98, 98), OPEN.plusMinutes(16)).orElseThrow();
            truth(signal.side.equals("SELL")); eq(101, signal.stopPrice);
            ORBStrategy longOnly = strategy(false); opening(longOnly, OPEN, 101, 99);
            truth(longOnly.onClosedCandle(bar(OPEN.plusMinutes(15),99,98,98),OPEN.plusMinutes(16)).isEmpty());
        });
        check("sizing uses risk cash notional liquidity and costs", () -> {
            eq(125, PositionSizer.quantity(100,98,250,100000,25000,1000,0));
            eq(10, PositionSizer.quantity(100,98,250,1000,25000,1000,0));
            eq(5, PositionSizer.quantity(100,98,250,100000,500,1000,0));
            eq(3, PositionSizer.quantity(100,98,250,100000,25000,3,0));
            eq(100, PositionSizer.quantity(100,98,250,100000,25000,1000,0.5));
            rejects(() -> PositionSizer.quantity(100,100,250,100000,25000,1000,0));
        });
        check("triggered exit retries after rejection even when price recovers", () -> {
            OrderManager o = orders(); fill(o, "BUY", 10, 100); RiskManager r = new RiskManager(o, 100000, 1000);
            r.setStopLoss("123", 95); r.checkPositions("123", 94);
            o.updateOrderStatus(o.getAllActiveOrders().get(0).orderId, "REJECTED", 0, 0);
            r.checkPositions("123", 100); eq(1, o.getAllActiveOrders().size());
        });
        check("small observation delay can signal before cutoff", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN, 101, 99);
            truth(s.onClosedCandle(bar(OPEN.plusMinutes(15),102,100,102), OPEN.plusMinutes(16).plusSeconds(2)).isPresent());
        });
        check("late old session input and backwards clock rejected", () -> {
            ORBStrategy s = strategy(false); opening(s, OPEN.plusDays(1), 101, 99);
            rejects(() -> s.onClosedCandle(bar(OPEN,101,99,100),OPEN.plusMinutes(1)));
            TimeSeriesManager t = new TimeSeriesManager(); t.addTick(tick(OPEN,100,10));
            t.advanceTime(OPEN.plusMinutes(1)); rejects(() -> t.advanceTime(OPEN));
        });
        System.out.println("Foundation checks passed: " + passed);
    }
}
