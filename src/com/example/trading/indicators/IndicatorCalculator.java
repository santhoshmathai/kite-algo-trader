package com.example.trading.indicators;

import com.example.trading.core.Candle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for calculating various technical indicators from candle data.
 * These methods can be used by strategies or other analytical components.
 */
public class IndicatorCalculator {

    public IndicatorCalculator() {
        // Constructor, can be empty if all methods are static,
        // or can hold configuration if needed.
    }

    /**
     * Calculates the Simple Moving Average (SMA).
     *
     * @param candles Deque of candles.
     * @param period  The period for the SMA (e.g., 10, 20).
     * @return The SMA value, or -1 if not enough data.
     */
    public static double calculateSMA(Deque<Candle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return -1; // Not enough data
        }

        // Get the last 'period' candles
        List<Candle> relevantCandles = new ArrayList<>(candles).subList(candles.size() - period, candles.size());

        double sum = 0;
        for (Candle candle : relevantCandles) {
            sum += candle.getClose(); // Typically SMA is on close prices
        }
        return sum / period;
    }

    /**
     * Calculates the Exponential Moving Average (EMA).
     * This is a simplified EMA calculation. For a more accurate one, especially for the first EMA value,
     * one might need to use an SMA as the starting point or process more historical data.
     *
     * @param candles Deque of candles.
     * @param period  The period for the EMA.
     * @return The EMA value, or -1 if not enough data.
     */
    public static double calculateEMA(Deque<Candle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return -1; // Not enough data
        }

        List<Double> closePrices = candles.stream().map(Candle::getClose).collect(Collectors.toList());

        // Calculate initial SMA for the first EMA value
        double sma = 0;
        for (int i = 0; i < period; i++) {
            sma += closePrices.get(i);
        }
        double previousEma = sma / period;

        // Calculate EMA for the rest of the series
        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < closePrices.size(); i++) {
            previousEma = (closePrices.get(i) - previousEma) * multiplier + previousEma;
        }
        return previousEma; // This is the EMA for the last candle in the deque
    }


    /**
     * Calculates the Relative Strength Index (RSI).
     *
     * @param candles Deque of candles.
     * @param period  The period for RSI (typically 14).
     * @return The RSI value, or -1 if not enough data.
     */
    public static double calculateRSI(Deque<Candle> candles, int period) {
        if (candles == null || candles.size() < period + 1) { // Need period+1 candles for 'period' changes
            return -1;
        }

        List<Double> closePrices = candles.stream().map(Candle::getClose).collect(Collectors.toList());
        // Take the last period + 1 prices to calculate 'period' changes
        List<Double> relevantPrices = closePrices.subList(closePrices.size() - (period + 1), closePrices.size());

        double averageGain = 0;
        double averageLoss = 0;

        // Calculate initial average gain/loss from the first 'period' changes
        for (int i = 1; i <= period; i++) {
            double change = relevantPrices.get(i) - relevantPrices.get(i - 1);
            if (change > 0) {
                averageGain += change;
            } else {
                averageLoss -= change; // Loss is positive
            }
        }
        averageGain /= period;
        averageLoss /= period;

        // Smooth RSI (Wilder's smoothing) - optional, classic Cutler's RSI doesn't always use this for subsequent values
        // For simplicity, we'll use the averages as is.
        // For a full implementation:
        // for (int i = period + 1; i < relevantPrices.size(); i++) {
        //     double change = relevantPrices.get(i) - relevantPrices.get(i-1);
        //     double currentGain = (change > 0) ? change : 0;
        //     double currentLoss = (change < 0) ? -change : 0;
        //     averageGain = (averageGain * (period - 1) + currentGain) / period;
        //     averageLoss = (averageLoss * (period - 1) + currentLoss) / period;
        // }


        if (averageLoss == 0) {
            return 100; // Avoid division by zero; if all losses are zero, RSI is 100
        }

        double rs = averageGain / averageLoss; // Relative Strength
        double rsi = 100 - (100 / (1 + rs));

        return rsi;
    }

    /**
     * Calculates the Volume Weighted Average Price (VWAP).
     * VWAP is typically calculated for the current trading day.
     * This implementation assumes the provided candles are for the current day, sorted by time.
     *
     * @param dailyCandles Deque of candles for the current trading day.
     * @return The VWAP value, or -1 if no candles or volume.
     */
    public static double calculateVWAP(Deque<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return -1;
        }

        double totalTypicalPriceVolume = 0;
        long totalVolume = 0;

        for (Candle candle : dailyCandles) {
            double typicalPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3.0;
            totalTypicalPriceVolume += typicalPrice * candle.getVolume();
            totalVolume += candle.getVolume();
        }

        if (totalVolume == 0) {
            return -1; // Avoid division by zero
        }

        return totalTypicalPriceVolume / totalVolume;
    }

    /**
     * Detects a volume spike.
     * A spike is defined as volume significantly higher than the average volume over a lookback period.
     *
     * @param candles      Deque of candles.
     * @param lookbackPeriod Period to calculate average volume.
     * @param spikeFactor  Factor by which current volume must exceed average (e.g., 2.0 for 2x average).
     * @return True if the last candle's volume is a spike, false otherwise.
     */
    public static boolean detectVolumeSpike(Deque<Candle> candles, int lookbackPeriod, double spikeFactor) {
        if (candles == null || candles.size() < lookbackPeriod + 1) { // +1 for the current candle
            return false; // Not enough data
        }

        List<Candle> lookbackCandles = new ArrayList<>(candles).subList(candles.size() - lookbackPeriod - 1, candles.size() - 1);
        Candle lastCandle = candles.getLast();

        if (lookbackCandles.isEmpty()) return false;

        long sumVolume = 0;
        for (Candle candle : lookbackCandles) {
            sumVolume += candle.getVolume();
        }
        double averageVolume = (double) sumVolume / lookbackPeriod;

        return lastCandle.getVolume() > (averageVolume * spikeFactor);
    }


    // Example usage (can be removed or moved to a test class)
    public static void main(String[] args) {
        // Simulate some candle data (e.g., for testing)
        // Deque<Candle> testCandles = new ArrayDeque<>();
        // ZonedDateTime time = ZonedDateTime.now();
        // testCandles.add(new Candle(time.minusMinutes(10), "TEST", 100, 102, 99, 101, 1000));
        // ... add more candles

        // double sma10 = calculateSMA(testCandles, 10);
        // System.out.println("SMA(10): " + sma10);

        // double rsi14 = calculateRSI(testCandles, 14);
        // System.out.println("RSI(14): " + rsi14);

        // double vwap = calculateVWAP(testCandles); // Assuming these are daily candles
        // System.out.println("VWAP: " + vwap);
    }
}
