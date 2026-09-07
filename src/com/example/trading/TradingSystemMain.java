package com.example.trading;

import com.example.trading.core.*;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.order.OrderManager;
import com.example.trading.risk.*;
import com.example.trading.strategy.ORBStrategy;
import com.example.trading.util.Config;
import java.time.*;
import java.util.*;

/** Explicitly offline, deterministic demonstration of the first milestone. */
public final class TradingSystemMain {
    public static void main(String[] args) {
        if (args.length != 1 || !"--demo".equals(args[0])) {
            System.out.println("Kite Algo Trader - offline foundation\nUsage: java -jar kite-algo-trader.jar --demo");
            System.out.println("Live execution and historical performance backtesting are not enabled.");
            if (args.length > 0 && !"--help".equals(args[0])) System.exit(2);
            return;
        }
        Config config = new Config();
        double capital = Double.parseDouble(config.getProperty("risk.capital", "100000"));
        double tradeRisk = Double.parseDouble(config.getProperty("risk.perTrade", "250"));
        double dailyLoss = Double.parseDouble(config.getProperty("risk.dailyLoss", "1000"));
        double maximumNotional = Double.parseDouble(config.getProperty("risk.maximumNotional", "25000"));
        double buffer = Double.parseDouble(config.getProperty("strategy.breakoutBuffer", "0.05"));
        double reward = Double.parseDouble(config.getProperty("strategy.rewardMultiple", "1.5"));
        if (!Double.isFinite(reward) || reward <= 0) throw new IllegalArgumentException("Invalid reward multiple");
        TradingSession session = new TradingSession();
        Instrument instrument = new Instrument("DEMO-1", "NSE", "DEMO_EQUITY", true);
        InstrumentRegistry registry = new InstrumentRegistry(); registry.register(instrument);
        TimeSeriesManager data = new TimeSeriesManager(session);
        OrderManager orders = new OrderManager(registry);
        RiskManager risk = new RiskManager(orders, capital, dailyLoss);
        ORBStrategy strategy = new ORBStrategy(instrument, session, 15, LocalTime.of(10, 0), buffer, false);
        ZonedDateTime start = ZonedDateTime.of(2026, 9, 7, 9, 15, 0, 0, TradingSession.ZONE);
        System.out.println("SYNTHETIC DEMO ONLY - no network, credentials, or real orders; P&L excludes fees.");
        for (int minute = 0; minute < 15; minute++) {
            Candle bar = new Candle(start.plusMinutes(minute), instrument.token, 100, 101, 99, 100, 1000);
            data.addClosedCandle(bar);
            strategy.onClosedCandle(bar, bar.getTimestamp().plusMinutes(1));
        }
        System.out.println("Opening range: " + data.getFifteenMinSeries(instrument.token).getLast());
        Candle breakout = new Candle(start.plusMinutes(15), instrument.token, 100, 102, 100, 102, 2000);
        data.addClosedCandle(breakout);
        ORBStrategy.Signal signal = strategy.onClosedCandle(breakout, start.plusMinutes(16)).orElseThrow();
        // A separately specified next observation supplies the fill: never the signal bar itself.
        double fill = 102.10;
        int qty = PositionSizer.quantity(fill, signal.stopPrice, tradeRisk, capital, maximumNotional, 1000, 0);
        if (qty == 0) { System.out.println("Sizing rejected this synthetic trade."); return; }
        String entryId = orders.placeOrder(Map.of("exchange", instrument.exchange, "tradingsymbol", instrument.symbol,
                "transaction_type", signal.side, "quantity", qty, "product", "MIS", "order_type", "LIMIT", "price", fill), "regular");
        orders.updateOrderStatus(entryId, "COMPLETE", qty, fill);
        double target = fill + reward * (fill - signal.stopPrice);
        risk.setStopLoss(instrument.token, signal.stopPrice);
        risk.setTakeProfit(instrument.token, target);
        System.out.printf(Locale.ROOT, "09:31 simulated BUY %d at %.2f; stop %.2f; target %.2f%n", qty, fill, signal.stopPrice, target);
        risk.checkPositions(instrument.token, target);
        OrderManager.OrderDetails exit = orders.getAllActiveOrders().get(0);
        orders.updateOrderStatus(exit.orderId, "COMPLETE", qty, target);
        risk.checkPositions(instrument.token, target);
        risk.checkDrawdown();
        System.out.printf(Locale.ROOT, "Simulated exit confirmed; gross P&L %.2f; open positions %d%n",
                orders.getTotalPnl(), orders.getAllOpenPositions().size());
        System.out.println("This fixture verifies the plumbing; it provides no evidence of a trading edge.");
    }
}
