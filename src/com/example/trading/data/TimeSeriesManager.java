package com.example.trading.data;

import com.example.trading.core.Candle;
import com.example.trading.core.TickData;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages time series data for various instruments and timeframes.
 * It receives ticks, aggregates them into 1-minute candles,
 * and then further aggregates them into higher timeframe candles (5-min, 15-min, etc.).
 */
public class TimeSeriesManager {

    // Key: Instrument Token (String), Value: Deque of 1-minute candles
    private final ConcurrentHashMap<String, Deque<Candle>> oneMinSeries = new ConcurrentHashMap<>();
    // Key: Instrument Token (String), Value: Deque of 5-minute candles
    private final ConcurrentHashMap<String, Deque<Candle>> fiveMinSeries = new ConcurrentHashMap<>();
    // Key: Instrument Token (String), Value: Deque of 15-minute candles
    private final ConcurrentHashMap<String, Deque<Candle>> fifteenMinSeries = new ConcurrentHashMap<>();

    private static final int MAX_CANDLES_PER_SERIES = 1000; // Max candles to keep per series to save memory

    public TimeSeriesManager() {
        System.out.println("TimeSeriesManager initialized.");
    }

    /**
     * Processes an incoming tick for a specific instrument.
     * Aggregates ticks into 1-minute candles.
     *
     * @param tick The TickData object.
     */
    public synchronized void addTick(TickData tick) {
        if (tick == null) return;

        String instrumentToken = tick.getInstrumentToken();
        oneMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>())
                    .compute(instrumentToken, (key, series) -> {
                        if (series == null) series = new ArrayDeque<>();

                        ZonedDateTime tickTime = tick.getTimestamp();
                        double price = tick.getLastTradedPrice();
                        long volume = tick.getLastTradedVolume();

                        if (series.isEmpty() || isNewCandlePeriod(series.getLast().getTimestamp(), tickTime, 1)) {
                            // Create a new 1-minute candle
                            ZonedDateTime candleStartTime = tickTime.truncatedTo(ChronoUnit.MINUTES);
                            Candle newCandle = new Candle(candleStartTime, instrumentToken, price, price, price, price, volume);
                            series.addLast(newCandle);
                            // System.out.println("New 1-min candle for " + instrumentToken + ": " + newCandle);
                        } else {
                            // Update the current 1-minute candle
                            Candle currentCandle = series.getLast();
                            currentCandle.update(price, volume);
                            // System.out.println("Updated 1-min candle for " + instrumentToken + ": " + currentCandle);
                        }

                        // Trim old candles if series is too long
                        trimOldCandles(series);
                        return series;
                    });

        // After updating 1-min series, check for aggregation to higher timeframes
        aggregateToHigherTimeFrames(instrumentToken);
    }

    /**
     * Checks if a new candle period has started based on the last candle's time and current tick time.
     *
     * @param lastCandleTime Timestamp of the last candle.
     * @param tickTime       Timestamp of the current tick.
     * @param minutes        The minute interval for the candle (e.g., 1 for 1-min, 5 for 5-min).
     * @return True if a new candle should be formed, false otherwise.
     */
    private boolean isNewCandlePeriod(ZonedDateTime lastCandleTime, ZonedDateTime tickTime, int minutes) {
        ZonedDateTime lastCandleEndTime = lastCandleTime.plusMinutes(minutes);
        return !tickTime.isBefore(lastCandleEndTime);
    }

    /**
     * Aggregates 1-minute candles into 5-minute and 15-minute candles.
     * This method should be called after new 1-minute candles are formed or updated.
     *
     * @param instrumentToken The instrument token for which to aggregate.
     */
    private synchronized void aggregateToHigherTimeFrames(String instrumentToken) {
        // Aggregate to 5-minute candles
        Deque<Candle> source1MinSeries = oneMinSeries.get(instrumentToken);
        if (source1MinSeries != null && !source1MinSeries.isEmpty()) {
            aggregate(source1MinSeries, fiveMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>()), instrumentToken, 5);
        }

        // Aggregate to 15-minute candles (from 5-minute candles for efficiency, or from 1-min if preferred)
        Deque<Candle> source5MinSeries = fiveMinSeries.get(instrumentToken);
        if (source5MinSeries != null && !source5MinSeries.isEmpty()) {
            aggregate(source5MinSeries, fifteenMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>()), instrumentToken, 3); // 3 * 5-min candles = 15 min
            // Alternative: if aggregating 15-min from 1-min directly:
            // aggregate(source1MinSeries, fifteenMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>()), instrumentToken, 15);
        }
    }

    /**
     * Generic aggregation logic.
     *
     * @param sourceSeries      The source Deque of candles (e.g., 1-min candles).
     * @param targetSeries      The target Deque of candles (e.g., 5-min candles).
     * @param instrumentToken   The instrument token.
     * @param aggregationFactor The number of source candles that make up one target candle
     *                          (e.g., 5 for 1-min to 5-min, 3 for 5-min to 15-min).
     *                          OR the minute value of the target candle if aggregating from base (e.g. 1-min) candles.
     * @param sourceIntervalMinutes The duration in minutes of each candle in the sourceSeries.
     */
    private void aggregate(Deque<Candle> sourceSeries, Deque<Candle> targetSeries, String instrumentToken, int targetIntervalMinutes) {
        // This is a simplified aggregation logic placeholder.
        // A more robust implementation would group source candles by the target interval.
        // For example, for a 5-minute candle, group all 1-minute candles whose timestamp falls within that 5-minute slot.

        if (sourceSeries.isEmpty()) return;

        ZonedDateTime lastSourceCandleTime = sourceSeries.getLast().getTimestamp();
        ZonedDateTime targetCandleStartTime;

        if (targetSeries.isEmpty() || isNewCandlePeriod(targetSeries.getLast().getTimestamp(), lastSourceCandleTime, targetIntervalMinutes)) {
            // Determine the start time for the new target candle
            targetCandleStartTime = lastSourceCandleTime.truncatedTo(ChronoUnit.MINUTES) // Start of the minute
                                        .minusMinutes(lastSourceCandleTime.getMinute() % targetIntervalMinutes); // Align to target interval

            // Collect relevant source candles for this new target candle
            List<Candle> candlesToAggregate = sourceSeries.stream()
                    .filter(c -> !c.getTimestamp().isBefore(targetCandleStartTime) && c.getTimestamp().isBefore(targetCandleStartTime.plusMinutes(targetIntervalMinutes)))
                    .collect(Collectors.toList());

            if (!candlesToAggregate.isEmpty()) {
                Candle newAggregatedCandle = createAggregatedCandle(candlesToAggregate, targetCandleStartTime, instrumentToken);
                // Avoid adding duplicate if already processed
                if (targetSeries.isEmpty() || !targetSeries.getLast().getTimestamp().equals(newAggregatedCandle.getTimestamp())) {
                    targetSeries.addLast(newAggregatedCandle);
                    // System.out.println("New " + targetIntervalMinutes + "-min candle for " + instrumentToken + ": " + newAggregatedCandle);
                    trimOldCandles(targetSeries);
                }
            }
        } else {
            // Update existing target candle
            Candle currentTargetCandle = targetSeries.getLast();
            targetCandleStartTime = currentTargetCandle.getTimestamp();

            List<Candle> candlesToAggregate = sourceSeries.stream()
                    .filter(c -> !c.getTimestamp().isBefore(targetCandleStartTime) && c.getTimestamp().isBefore(targetCandleStartTime.plusMinutes(targetIntervalMinutes)))
                    .collect(Collectors.toList());

            if (!candlesToAggregate.isEmpty()) {
                updateAggregatedCandle(currentTargetCandle, candlesToAggregate);
                // System.out.println("Updated " + targetIntervalMinutes + "-min candle for " + instrumentToken + ": " + currentTargetCandle);
            }
        }
    }


    /**
     * Creates an aggregated candle from a list of smaller candles.
     */
    private Candle createAggregatedCandle(List<Candle> candles, ZonedDateTime startTime, String instrumentToken) {
        if (candles == null || candles.isEmpty()) return null;

        double open = candles.get(0).getOpen();
        double high = candles.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double low = candles.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double close = candles.get(candles.size() - 1).getClose();
        long volume = candles.stream().mapToLong(Candle::getVolume).sum();

        return new Candle(startTime, instrumentToken, open, high, low, close, volume);
    }

    /**
    * Updates an existing aggregated candle with new data from a list of smaller candles.
    * This is used if the source candles contributing to the current aggregated candle have been updated.
    */
    private void updateAggregatedCandle(Candle aggregatedCandle, List<Candle> contributingCandles) {
        if (contributingCandles == null || contributingCandles.isEmpty()) return;

        // Open remains the same
        // aggregatedCandle.setOpen(contributingCandles.get(0).getOpen()); // Open is from the first contributing candle of its period

        double high = contributingCandles.stream().mapToDouble(Candle::getHigh).max().orElse(aggregatedCandle.getHigh());
        aggregatedCandle.setHigh(high);

        double low = contributingCandles.stream().mapToDouble(Candle::getLow).min().orElse(aggregatedCandle.getLow());
        aggregatedCandle.setLow(low);

        aggregatedCandle.setClose(contributingCandles.get(contributingCandles.size() - 1).getClose());

        long volume = contributingCandles.stream().mapToLong(Candle::getVolume).sum();
        aggregatedCandle.addVolume(volume - aggregatedCandle.getVolume()); // Add diff, or set total directly if easier
                                                                         // Setting total directly: aggregatedCandle.setVolume(volume) - but Candle needs setVolume
                                                                         // For now, using addVolume which assumes the 'volume' param is incremental.
                                                                         // Correct approach for re-aggregation: reset volume and then sum.
                                                                         // Let's assume createAggregatedCandle is used for new, and this updates based on latest state.
                                                                         // So, the passed volume should be the *new total volume* for the period.
                                                                         // This requires Candle to have setVolume, or a more complex update.
                                                                         // For simplicity, let's re-calculate total volume for the update.
        // Re-calculate volume for the entire period of the aggregated candle
        // This requires knowing all base candles that form this aggregated candle,
        // which the current `contributingCandles` list provides for the *latest update cycle*.
        // A truly robust update would re-evaluate from all relevant source candles.
        // The current `update` method in `Candle` is for tick-level updates.
        // For aggregated candles, it's better to just recalculate:
        aggregatedCandle.setHigh(Math.max(aggregatedCandle.getHigh(), high)); // Ensure high is highest seen
        aggregatedCandle.setLow(Math.min(aggregatedCandle.getLow(), low));   // Ensure low is lowest seen
        // Close is the close of the last contributing candle
        // Volume is the sum of volumes of all contributing candles for this aggregated period.
        // This simplified `updateAggregatedCandle` assumes `contributingCandles` are the *complete set* for the period.
        // This is tricky with ongoing updates. A more common pattern is to simply rebuild the aggregated candle
        // if any of its constituent parts change, or to aggregate only when a source period closes.

        // A simpler model for this skeleton: when a source candle closes, trigger aggregation.
        // The current `aggregate` method tries to do this by creating a new target candle or updating the last one.
        // The update path in `aggregate` should use the latest state of source candles.
    }


    /**
     * Trims the candle series to maintain a maximum size.
     *
     * @param series The Deque of candles to trim.
     */
    private void trimOldCandles(Deque<Candle> series) {
        while (series.size() > MAX_CANDLES_PER_SERIES) {
            series.removeFirst();
        }
    }

    // --- Getters for time series data ---

    public Deque<Candle> getOneMinSeries(String instrumentToken) {
        return new ArrayDeque<>(oneMinSeries.getOrDefault(instrumentToken, new ArrayDeque<>()));
    }

    public Deque<Candle> getFiveMinSeries(String instrumentToken) {
        return new ArrayDeque<>(fiveMinSeries.getOrDefault(instrumentToken, new ArrayDeque<>()));
    }

    public Deque<Candle> getFifteenMinSeries(String instrumentToken) {
        return new ArrayDeque<>(fifteenMinSeries.getOrDefault(instrumentToken, new ArrayDeque<>()));
    }

    /**
     * Loads historical candles into the appropriate series.
     * This is useful for initializing the manager with past data.
     *
     * @param instrumentToken The instrument token.
     * @param candles         List of historical candles.
     * @param intervalMinutes The interval of the provided candles (e.g., 1, 5, 15).
     */
    public synchronized void loadHistoricalCandles(String instrumentToken, List<Candle> candles, int intervalMinutes) {
        if (candles == null || candles.isEmpty()) return;

        Deque<Candle> targetSeries;
        switch (intervalMinutes) {
            case 1:
                targetSeries = oneMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>());
                break;
            case 5:
                targetSeries = fiveMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>());
                break;
            case 15:
                targetSeries = fifteenMinSeries.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>());
                break;
            default:
                System.err.println("Unsupported interval for loading historical candles: " + intervalMinutes);
                return;
        }

        // Sort candles by timestamp if not already sorted (important for correct order)
        candles.sort((c1, c2) -> c1.getTimestamp().compareTo(c2.getTimestamp()));

        for (Candle candle : candles) {
            if (targetSeries.size() >= MAX_CANDLES_PER_SERIES) {
                targetSeries.removeFirst(); // Make space if full
            }
            targetSeries.addLast(candle);
        }
        System.out.println("Loaded " + candles.size() + " historical " + intervalMinutes + "-min candles for " + instrumentToken);

        // After loading historical 1-min candles, one might want to trigger aggregation
        // for higher timeframes if these were not loaded directly.
        if (intervalMinutes == 1) {
             // This needs careful handling to ensure correct aggregation from historical data.
             // The current live aggregation logic might need adjustment or a separate path for batch historical aggregation.
             // For now, we assume historical data is loaded for each required timeframe separately or aggregation is handled post-load.
            System.out.println("Historical 1-min data loaded. Consider running aggregation for " + instrumentToken + " if needed.");
        }
    }
}
