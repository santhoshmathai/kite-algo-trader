package com.example.trading.strategy;

import com.example.trading.core.Candle;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.indicators.IndicatorCalculator; // For volume spike detection
import com.example.trading.order.OrderManager;
import com.example.trading.util.LoggingUtil;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Implements an enhanced Opening Range Breakout (ORB) strategy.
 * - Fixed 15-minute opening range.
 * - Incorporates gap analysis (vs. Previous Day Close - PDC).
 * - Uses volume spikes and price change momentum for trade confirmation.
 */
public class ORBStrategy {

    private enum GapStatus { NONE, GAP_UP, GAP_DOWN }

    private final TimeSeriesManager timeSeriesManager;
    private final OrderManager orderManager;
    private final String instrumentSymbol; // Trading symbol, e.g., "NIFTYBANK"
    private final String numericalInstrumentToken; // Numerical token for data, e.g., "260105"
    private final double previousDayClose;
    private final int openingRangeDurationMinutes; // Fixed at 15 minutes as per requirement
    private final LocalTime marketOpenTime;
    private final LocalTime strategyEndTime;

    // Dynamic state variables
    private double todaysOpenPrice = -1;
    private GapStatus gapStatus = GapStatus.NONE;
    private double openingRangeHigh = -1;
    private double openingRangeLow = -1;
    private boolean openingRangeEstablished = false;
    private boolean longTradeTakenToday = false;
    private boolean shortTradeTakenToday = false;
    private String activeBuyOrderId = null;
    private String activeSellOrderId = null;

    // Parameters for volume and price change analysis (can be made configurable)
    private final int volumeLookbackPeriod = 10; // For average volume calculation
    private final double volumeSpikeFactor = 1.5;  // e.g., current volume > 1.5x average
    private final int priceChangeLookbackCandles = 5; // For price change momentum (N candles)
    private final double minDayRangePercentForSignal = 2.0; // Minimum % range (dayHigh-dayLow) for signal
    private final long minTotalDayVolumeForSignal = 1000000; // Minimum total volume for the day

    // Tracking for post-signal analysis (as in example)
    private double priceAtSignal = -1;
    private double lowAfterLongSignal = Double.MAX_VALUE;
    private double highAfterShortSignal = Double.MIN_VALUE;
    private LocalTime conditionMetTime = null;

    // Dynamic day metrics
    private double currentDayHigh = Double.MIN_VALUE;
    private double currentDayLow = Double.MAX_VALUE;
    private long currentTotalDayVolume = 0;


    public ORBStrategy(TimeSeriesManager timeSeriesManager, OrderManager orderManager,
                       String instrumentSymbol, String numericalInstrumentToken, double previousDayClose,
                       int openingRangeDurationMinutes, LocalTime marketOpenTime, LocalTime strategyEndTime) {
        this.timeSeriesManager = timeSeriesManager;
        this.orderManager = orderManager;
        this.instrumentSymbol = instrumentSymbol;
        this.numericalInstrumentToken = numericalInstrumentToken;
        this.previousDayClose = previousDayClose;
        this.openingRangeDurationMinutes = openingRangeDurationMinutes; // Should be 15
        this.marketOpenTime = marketOpenTime;
        this.strategyEndTime = strategyEndTime;

        LoggingUtil.info(String.format("ORBStrategy initialized for %s (Token: %s, PDC: %.2f): OR Duration: %d min, Market Open: %s, Strategy End: %s",
                instrumentSymbol, numericalInstrumentToken, previousDayClose,
                this.openingRangeDurationMinutes, marketOpenTime.toString(), strategyEndTime.toString()));

        if (this.openingRangeDurationMinutes != 15) {
            LoggingUtil.warning(String.format("ORBStrategy for %s: Opening range duration is %d, but requirement is 15 minutes. Using %d.",
                                instrumentSymbol, this.openingRangeDurationMinutes, this.openingRangeDurationMinutes));
        }
    }

    public String getInstrumentSymbol() { return instrumentSymbol; }
    public String getNumericalInstrumentToken() { return numericalInstrumentToken; }


    public void evaluate() {
        Deque<Candle> oneMinCandles = timeSeriesManager.getOneMinSeries(numericalInstrumentToken);
        if (oneMinCandles.isEmpty()) {
            return;
        }

        Candle lastCandle = oneMinCandles.getLast();
        LocalTime lastCandleTime = lastCandle.getTimestamp().toLocalTime();

        // Update dynamic day metrics with each new 1-min candle
        updateDailyMetrics(lastCandle);

        // 1. Detect Gap and Today's Open (once at the start of the day)
        if (todaysOpenPrice == -1 && lastCandleTime.isAfter(marketOpenTime.minusMinutes(1)) && lastCandleTime.isBefore(marketOpenTime.plusMinutes(openingRangeDurationMinutes))) {
            // Use the open of the first available candle at or just after market open time
            Candle firstCandleOfTheDay = findFirstCandleOfDay(oneMinCandles);
            if (firstCandleOfTheDay != null) {
                this.todaysOpenPrice = firstCandleOfTheDay.getOpen();
                detectGap();
            }
        }

        // 2. Establish Opening Range (first 15 minutes)
        // The condition `lastCandleTime.isAfter(marketOpenTime.plusMinutes(openingRangeDurationMinutes))` ensures we wait till the range period is over.
        // However, range establishment should use candles strictly within the ORB period.
        if (!openingRangeEstablished && lastCandleTime.isAfter(marketOpenTime.plusMinutes(openingRangeDurationMinutes -1)) ) {
             // -1 to ensure the candle forming at marketOpenTime + duration is included for check after its close.
            establishOpeningRange(oneMinCandles);
        }

        // 3. Check for Breakouts if range is established and within trading time
        if (openingRangeEstablished && !lastCandleTime.isAfter(strategyEndTime) && conditionMetTime == null) {
            // conditionMetTime == null ensures we only take one signal per day based on initial conditions
            checkForBreakout(lastCandle, oneMinCandles);
        }

        // 4. Track price movement after signal (as per example)
        if (conditionMetTime != null) {
            if (longTradeTakenToday && lastCandle.getLow() < lowAfterLongSignal) {
                lowAfterLongSignal = lastCandle.getLow();
            }
            if (shortTradeTakenToday && lastCandle.getHigh() > highAfterShortSignal) {
                highAfterShortSignal = lastCandle.getHigh();
            }
            // TODO: Logic for alerting based on profit/loss from priceAtSignal, similar to example.
            // This might involve checking if (priceAtSignal - lowAfterLongSignal) / lowAfterLongSignal * 100 > some_SL_percent
        }
    }

    private Candle findFirstCandleOfDay(Deque<Candle> oneMinCandles) {
        for (Candle c : oneMinCandles) {
            if (!c.getTimestamp().toLocalTime().isBefore(marketOpenTime)) {
                return c;
            }
        }
        return null; // Should not happen if evaluate is called with candles
    }

    private void updateDailyMetrics(Candle candle) {
        if (candle.getHigh() > currentDayHigh) {
            currentDayHigh = candle.getHigh();
        }
        if (candle.getLow() < currentDayLow || currentDayLow == Double.MAX_VALUE) { // Initialize currentDayLow properly
            currentDayLow = candle.getLow();
        }
        // currentTotalDayVolume should be sum of all 1-min candle volumes for the day.
        // Assuming TimeSeriesManager provides full day's 1-min candles or we accumulate here.
        // For simplicity, this example assumes TimeSeriesManager gives us all candles for the day for this instrument.
        // A more accurate way: get all 1-min candles and sum their volumes.
        // currentTotalDayVolume = oneMinCandles.stream().mapToLong(Candle::getVolume).sum(); // This might be inefficient if called every time.
        // A better way is if Candle itself has cumulative volume for the day, or TimeSeriesManager provides it,
        // or we sum it once per evaluation based on the candles available.
        // For now, let's assume the last candle in a full day series would have this, or we sum it:
        if (timeSeriesManager.getOneMinSeries(numericalInstrumentToken) != null) {
             currentTotalDayVolume = timeSeriesManager.getOneMinSeries(numericalInstrumentToken)
                                         .stream()
                                         .filter(c -> c.getTimestamp().toLocalDate().equals(candle.getTimestamp().toLocalDate()))
                                         .mapToLong(Candle::getVolume).sum();
        }
    }


    private void detectGap() {
        if (previousDayClose <= 0 || todaysOpenPrice <= 0) {
            LoggingUtil.warning(String.format("ORB (%s): Cannot determine gap. PDC: %.2f, Today's Open: %.2f",
                    instrumentSymbol, previousDayClose, todaysOpenPrice));
            this.gapStatus = GapStatus.NONE;
            return;
        }

        double gapPercentage = ((todaysOpenPrice - previousDayClose) / previousDayClose) * 100;
        if (todaysOpenPrice > previousDayClose) {
            this.gapStatus = GapStatus.GAP_UP;
            LoggingUtil.info(String.format("ORB (%s): GAP UP detected. PDC: %.2f, Open: %.2f (%.2f%%)",
                    instrumentSymbol, previousDayClose, todaysOpenPrice, gapPercentage));
        } else if (todaysOpenPrice < previousDayClose) {
            this.gapStatus = GapStatus.GAP_DOWN;
            LoggingUtil.info(String.format("ORB (%s): GAP DOWN detected. PDC: %.2f, Open: %.2f (%.2f%%)",
                    instrumentSymbol, previousDayClose, todaysOpenPrice, gapPercentage));
        } else {
            this.gapStatus = GapStatus.NONE;
            LoggingUtil.info(String.format("ORB (%s): No significant gap. PDC: %.2f, Open: %.2f",
                    instrumentSymbol, previousDayClose, todaysOpenPrice));
        }
    }

    private void establishOpeningRange(Deque<Candle> oneMinCandles) {
        LocalTime openingRangeEndTime = marketOpenTime.plusMinutes(openingRangeDurationMinutes);
        double high = Double.MIN_VALUE;
        double low = Double.MAX_VALUE;
        boolean foundCandlesInRange = false;

        // Filter candles that fall strictly within the opening range period.
        List<Candle> rangeCandles = oneMinCandles.stream()
            .filter(c -> {
                LocalTime candleTime = c.getTimestamp().toLocalTime();
                // Candle start time should be >= marketOpenTime and < openingRangeEndTime
                return !candleTime.isBefore(marketOpenTime) && candleTime.isBefore(openingRangeEndTime);
            })
            .collect(Collectors.toList());

        if (rangeCandles.isEmpty()) {
             LoggingUtil.warning(String.format("ORB (%s): No 1-minute candles found within the %d-min opening range period (%s to %s). Cannot establish range.",
                instrumentSymbol, openingRangeDurationMinutes, marketOpenTime, openingRangeEndTime));
            return;
        }

        for (Candle candle : rangeCandles) {
            if (candle.getHigh() > high) high = candle.getHigh();
            if (candle.getLow() < low) low = candle.getLow();
            foundCandlesInRange = true;
        }

        if (foundCandlesInRange) {
            this.openingRangeHigh = high;
            this.openingRangeLow = low;
            this.openingRangeEstablished = true;
            LoggingUtil.info(String.format("ORB (%s): %d-min Opening Range established: High=%.2f, Low=%.2f",
                    instrumentSymbol, openingRangeDurationMinutes, openingRangeHigh, openingRangeLow));
        } else {
            // This case should ideally be caught by rangeCandles.isEmpty() check above.
            LoggingUtil.warning(String.format("ORB (%s): Could not establish opening range despite having candles. Logic error?", instrumentSymbol));
        }
    }

    private static class VolumeMomentumAnalysis {
        boolean volumeSpike = false;
        boolean positivePriceMomentum = false; // True if current price change > previous price change
        boolean negativePriceMomentum = false; // True if current price change < previous price change (more negative)
    }

    private VolumeMomentumAnalysis analyzeVolumeAndPriceMomentum(Candle currentCandle, Deque<Candle> oneMinCandles) {
        VolumeMomentumAnalysis result = new VolumeMomentumAnalysis();
        if (oneMinCandles.size() < (priceChangeLookbackCandles * 2) + volumeLookbackPeriod) {
            LoggingUtil.debug(String.format("ORB (%s): Not enough candles for full volume/momentum analysis (need %d, have %d)",
                instrumentSymbol, (priceChangeLookbackCandles * 2) + volumeLookbackPeriod, oneMinCandles.size()));
            return result; // Not enough data
        }

        // Volume Spike Analysis (using IndicatorCalculator or simple logic here)
        // We need a sub-list of candles *before* the currentCandle for average volume.
        List<Candle> historicalCandles = new ArrayList<>(oneMinCandles);
        int currentCandleIndex = historicalCandles.indexOf(currentCandle);
        if (currentCandleIndex < volumeLookbackPeriod) {
             LoggingUtil.debug(String.format("ORB (%s): Not enough historical candles before current for volume spike analysis.", instrumentSymbol));
            return result; // Not enough preceding candles
        }
        // Candles for avg volume calculation are those before the current one.
        Deque<Candle> volumeAvgCandles = new ArrayDeque<>(historicalCandles.subList(Math.max(0, currentCandleIndex - volumeLookbackPeriod), currentCandleIndex));

        if (!volumeAvgCandles.isEmpty()) {
            long sumVolume = 0;
            for (Candle c : volumeAvgCandles) sumVolume += c.getVolume();
            double avgVolume = (double) sumVolume / volumeAvgCandles.size();
            if (currentCandle.getVolume() > avgVolume * volumeSpikeFactor) {
                result.volumeSpike = true;
            }
        }

        // Price Change Momentum Analysis (similar to example)
        // Ensure we have enough candles for lookbacks. currentCandle is the latest.
        // We need 2*priceChangeLookbackCandles *before* the current candle to make comparisons
        // Example: current is at index `i`. We need candles from `i-1` down to `i - (2*priceChangeLookbackCandles)`
        if (currentCandleIndex >= (priceChangeLookbackCandles * 2)) {
            // Price change over the most recent N candles (excluding current, using candle just before it as end)
            // Example: if N=5, this is (close[i-1] - close[i-1-5])
            double recentPriceChange = historicalCandles.get(currentCandleIndex - 1).getClose() - historicalCandles.get(currentCandleIndex - 1 - priceChangeLookbackCandles).getClose();
            // Price change over the N candles prior to that
            // Example: (close[i-1-5] - close[i-1-10])
            double previousPriceChange = historicalCandles.get(currentCandleIndex - 1 - priceChangeLookbackCandles).getClose() - historicalCandles.get(currentCandleIndex - 1 - (2 * priceChangeLookbackCandles)).getClose();

            if (recentPriceChange > previousPriceChange) {
                result.positivePriceMomentum = true;
            }
            if (recentPriceChange < previousPriceChange) { // For short considerations
                result.negativePriceMomentum = true;
            }
        }

        LoggingUtil.debug(String.format("ORB (%s): VolSpike: %b, PosMomentum: %b, NegMomentum: %b", instrumentSymbol, result.volumeSpike, result.positivePriceMomentum, result.negativePriceMomentum));
        return result;
    }


    private void checkForBreakout(Candle lastCandle, Deque<Candle> oneMinCandles) {
        if (openingRangeHigh <= 0 || openingRangeLow <= 0) {
            LoggingUtil.debug(String.format("ORB (%s): Opening range H:%.2f L:%.2f not valid for breakout check.", instrumentSymbol, openingRangeHigh, openingRangeLow));
            return;
        }

        VolumeMomentumAnalysis analysis = analyzeVolumeAndPriceMomentum(lastCandle, oneMinCandles);

        // Common conditions from example
        double currentDayRangePercent = (currentDayHigh > 0 && currentDayLow > 0 && currentDayLow != Double.MAX_VALUE) ? ((currentDayHigh - currentDayLow) / currentDayLow) * 100 : 0;
        boolean overallConditionsMet = currentDayRangePercent > minDayRangePercentForSignal &&
                                       this.todaysOpenPrice < currentDayHigh && // Today's open is below current day high
                                       currentTotalDayVolume > minTotalDayVolumeForSignal &&
                                       analysis.volumeSpike;
                                       // PDC < currentDayHigh (similar to pdh < dayHigh in example)
                                       // Using PDC directly or ORB high based on context.
                                       // Example logic: previousDayClose < currentDayHigh

        // Long Breakout Logic
        if (!longTradeTakenToday && lastCandle.getClose() > openingRangeHigh) {
            LoggingUtil.info(String.format("ORB (%s): Potential Long Breakout. Price: %.2f > ORB High: %.2f",
                    instrumentSymbol, lastCandle.getClose(), openingRangeHigh));

            // Apply additional filters: Gap, Volume, Momentum, Overall Conditions
            // Example: Must be a gap up OR flat open for long, and positive momentum
            boolean gapConditionForLong = (gapStatus == GapStatus.GAP_UP || gapStatus == GapStatus.NONE);

            if (gapConditionForLong && overallConditionsMet && analysis.positivePriceMomentum) {
                 LoggingUtil.info(String.format("ORB (%s): LONG SIGNAL CONFIRMED. Gap: %s, VolumeSpike: %b, PosMomentum: %b, DayRange%%: %.2f, DayVol: %d",
                    instrumentSymbol, gapStatus, analysis.volumeSpike, analysis.positivePriceMomentum, currentDayRangePercent, currentTotalDayVolume));

                // Place Buy Order
                Map<String, Object> orderParams = createOrderParams("BUY", 1, lastCandle.getClose(), "LIMIT"); // TODO: Quantity from config
                activeBuyOrderId = orderManager.placeOrder(orderParams, "regular");
                if (activeBuyOrderId != null) {
                    LoggingUtil.info(String.format("ORB (%s): Buy order placed. ID: %s", instrumentSymbol, activeBuyOrderId));
                    longTradeTakenToday = true;
                    priceAtSignal = lastCandle.getClose();
                    lowAfterLongSignal = lastCandle.getLow(); // Initialize tracking
                    conditionMetTime = lastCandle.getTimestamp().toLocalTime();
                } else {
                    LoggingUtil.error(String.format("ORB (%s): Failed to place buy order.", instrumentSymbol));
                }
            } else {
                 LoggingUtil.info(String.format("ORB (%s): Long breakout NOT confirmed. GapCond: %b, OverallCond: %b, PosMomentum: %b",
                    instrumentSymbol, gapConditionForLong, overallConditionsMet, analysis.positivePriceMomentum));
            }
        }
        // Short Breakout Logic
        else if (!shortTradeTakenToday && lastCandle.getClose() < openingRangeLow) {
            LoggingUtil.info(String.format("ORB (%s): Potential Short Breakout. Price: %.2f < ORB Low: %.2f",
                    instrumentSymbol, lastCandle.getClose(), openingRangeLow));

            boolean gapConditionForShort = (gapStatus == GapStatus.GAP_DOWN || gapStatus == GapStatus.NONE);

            if (gapConditionForShort && overallConditionsMet && analysis.negativePriceMomentum) { // Use negative momentum for shorts
                 LoggingUtil.info(String.format("ORB (%s): SHORT SIGNAL CONFIRMED. Gap: %s, VolumeSpike: %b, NegMomentum: %b, DayRange%%: %.2f, DayVol: %d",
                    instrumentSymbol, gapStatus, analysis.volumeSpike, analysis.negativePriceMomentum, currentDayRangePercent, currentTotalDayVolume));

                Map<String, Object> orderParams = createOrderParams("SELL", 1, lastCandle.getClose(), "LIMIT"); // TODO: Quantity from config
                activeSellOrderId = orderManager.placeOrder(orderParams, "regular");
                if (activeSellOrderId != null) {
                    LoggingUtil.info(String.format("ORB (%s): Sell order placed. ID: %s", instrumentSymbol, activeSellOrderId));
                    shortTradeTakenToday = true;
                    priceAtSignal = lastCandle.getClose();
                    highAfterShortSignal = lastCandle.getHigh(); // Initialize tracking
                    conditionMetTime = lastCandle.getTimestamp().toLocalTime();
                } else {
                    LoggingUtil.error(String.format("ORB (%s): Failed to place sell order.", instrumentSymbol));
                }
            } else {
                 LoggingUtil.info(String.format("ORB (%s): Short breakout NOT confirmed. GapCond: %b, OverallCond: %b, NegMomentum: %b",
                    instrumentSymbol, gapConditionForShort, overallConditionsMet, analysis.negativePriceMomentum));
            }
        }
    }

    private Map<String, Object> createOrderParams(String transactionType, int quantity, double price, String orderType) {
        Map<String, Object> params = new HashMap<>();
        // IMPORTANT: Kite Connect API uses 'tradingsymbol' for orders, not the numerical instrument_token.
        params.put("tradingsymbol", this.instrumentSymbol);
        params.put("exchange", "NSE"); // TODO: Make configurable if supporting other exchanges like NFO,BSE
        params.put("transaction_type", transactionType);
        params.put("quantity", quantity); // TODO: Get quantity from config or dynamic calculation
        params.put("product", "MIS"); // Intraday product. TODO: Make configurable
        params.put("order_type", orderType); // e.g., "LIMIT", "MARKET"

        if ("LIMIT".equals(orderType) || "SL".equals(orderType)) {
            params.put("price", price);
        }
        // Add other params like trigger_price for SL/SL-M if needed.
        // params.put("tag", "ORB_" + instrumentSymbol); // Optional tag
        return params;
    }

    public void reset() {
        this.todaysOpenPrice = -1;
        this.gapStatus = GapStatus.NONE;
        this.openingRangeHigh = -1;
        this.openingRangeLow = -1;
        this.openingRangeEstablished = false;
        this.longTradeTakenToday = false;
        this.shortTradeTakenToday = false;
        this.activeBuyOrderId = null;
        this.activeSellOrderId = null;
        this.priceAtSignal = -1;
        this.lowAfterLongSignal = Double.MAX_VALUE;
        this.highAfterShortSignal = Double.MIN_VALUE;
        this.conditionMetTime = null;
        this.currentDayHigh = Double.MIN_VALUE;
        this.currentDayLow = Double.MAX_VALUE;
        this.currentTotalDayVolume = 0;
        LoggingUtil.info(String.format("ORBStrategy for %s has been reset for the new day.", instrumentSymbol));
    }
}
