package com.example.trading.kite;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.User;
import com.zerodhatech.models.Order; // For potential future use (order updates, placement response)
import com.zerodhatech.models.OrderParams; // For potential future use (placing orders)

import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnTicks;
import com.zerodhatech.models.Tick; // Correct model for live ticks from KiteTicker
// import com.zerodhatech.ticker.OnOrderUpdate; // If using order updates via WebSocket

import com.example.trading.core.Candle;
import com.example.trading.core.TickData;
import com.example.trading.core.MarketDepth; // For our MarketDepth model
import com.zerodhatech.models.Depth; // For Kite's depth model within a Tick

import com.example.trading.util.LoggingUtil;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class KiteService {

    private KiteConnect kiteConnect;
    private KiteTicker kiteTicker;
    private String apiKey;
    private String userId;
    private String accessToken;
    private String publicToken;

    private Consumer<TickData> onTickCallback;
    private Consumer<MarketDepth> onDepthCallback; // For full depth updates if subscribed separately
    private Runnable onWebSocketConnectCallback;
    private Runnable onWebSocketDisconnectCallback;
    private Consumer<String> onWebSocketErrorCallback; // For general WebSocket errors
    private Consumer<KiteException> onKiteExceptionCallback; // For specific KiteExceptions (REST API mostly)
    // private Consumer<ArrayList<Tick>> onWebSocketTicksCallback; // Raw ticks if needed


    public KiteService(String apiKey, String userId) {
        this.apiKey = apiKey;
        this.userId = userId;
        this.kiteConnect = new KiteConnect(this.apiKey);
        this.kiteConnect.setUserId(this.userId);
        LoggingUtil.info("KiteService initialized with API Key: " + apiKey + " and User ID: " + userId);
        LoggingUtil.info("Kite Login URL (for obtaining request_token): " + this.kiteConnect.getLoginURL());
    }

    public void setTokens(String accessToken, String publicToken) {
        this.accessToken = accessToken;
        this.publicToken = publicToken;
        this.kiteConnect.setAccessToken(this.accessToken);
        if (this.publicToken != null) {
            this.kiteConnect.setPublicToken(this.publicToken);
        }
        LoggingUtil.info("Access token set in KiteService. Public token " + (this.publicToken != null ? "set." : "not set."));
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
        this.kiteConnect.setAccessToken(accessToken);
        LoggingUtil.info("Access token set. Public token may need to be set via setTokens or after generateSession for WebSocket.");
    }

    public String getAccessToken() {
        return this.accessToken;
    }

    public String getPublicToken() {
        return this.publicToken;
    }

    public void generateSession(String requestToken, String apiSecret) {
        try {
            User user = kiteConnect.generateSession(requestToken, apiSecret);
            setTokens(user.accessToken, user.publicToken);
            LoggingUtil.info("KiteConnect session generated successfully for user: " + user.userName);
        } catch (KiteException e) {
            LoggingUtil.error("KiteException during session generation: " + e.getMessage() + ", Code: " + e.getCode(), e);
            if (onKiteExceptionCallback != null) onKiteExceptionCallback.accept(e);
        } catch (Exception e) {
            LoggingUtil.error("Unexpected error during session generation: " + e.getMessage(), e);
        }
    }

    public void generateSession(String requestToken) {
        LoggingUtil.error("Simplified generateSession(requestToken) called. API secret is required. Use generateSession(requestToken, apiSecret).");
        if (onKiteExceptionCallback != null) {
            onKiteExceptionCallback.accept(new KiteException("API Secret is required for session generation.", 0));
        }
    }

    public List<Candle> getHistoricalData(String instrumentToken, LocalDate fromDate, LocalDate toDate, String interval) {
        LoggingUtil.info("Fetching historical data for token " + instrumentToken + " from " + fromDate + " to " + toDate + " interval " + interval);
        if (this.accessToken == null) {
            LoggingUtil.error("Access token is null. Cannot fetch historical data for " + instrumentToken);
            return new ArrayList<>();
        }
        try {
            Date from = Date.from(fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date to = Date.from(toDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant());
            HistoricalData historicalData = kiteConnect.getHistoricalData(from, to, instrumentToken, interval, false, false);
            return transformToCandles(historicalData, instrumentToken);
        } catch (KiteException e) {
            LoggingUtil.error("KiteException fetching historical data for " + instrumentToken + ": " + e.getMessage() + " Code: " + e.code, e);
            if (onKiteExceptionCallback != null) onKiteExceptionCallback.accept(e);
        } catch (Exception e) {
            LoggingUtil.error("Unexpected error fetching historical data for " + instrumentToken + ": " + e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    private List<Candle> transformToCandles(HistoricalData kiteHistoricalData, String instrumentToken) {
        List<Candle> candles = new ArrayList<>();
        if (kiteHistoricalData != null && kiteHistoricalData.dataArrayList != null) {
            SimpleDateFormat kiteTimestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
            for (com.zerodhatech.models.HistoricalData.CandleData kd : kiteHistoricalData.dataArrayList) {
                ZonedDateTime zdt = null;
                try {
                    if (kd.timeStamp instanceof String) {
                        Date parsedDate = kiteTimestampFormat.parse((String) kd.timeStamp);
                        zdt = parsedDate.toInstant().atZone(ZoneId.systemDefault());
                    } else if (kd.timeStamp instanceof Date) {
                        zdt = ((Date) kd.timeStamp).toInstant().atZone(ZoneId.systemDefault());
                    } else {
                        LoggingUtil.warning("Unknown timestamp type for historical data: " + kd.timeStamp.getClass().getName() + " for token " + instrumentToken);
                        continue;
                    }
                    candles.add(new Candle(zdt, instrumentToken, kd.open, kd.high, kd.low, kd.close, kd.volume));
                } catch (ParseException e) {
                    LoggingUtil.error("Error parsing date string from historical data: " + kd.timeStamp + " for token " + instrumentToken, e);
                }
            }
        }
        return candles;
    }

    private LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate prevDate = date.minusDays(1);
        while (prevDate.getDayOfWeek() == DayOfWeek.SATURDAY || prevDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            prevDate = prevDate.minusDays(1);
        }
        return prevDate;
    }

    public double getPreviousDayHigh(String instrumentToken, LocalDate targetDate) {
        LoggingUtil.info("Fetching Previous Day High for " + instrumentToken + " relative to " + targetDate);
        LocalDate previousTradingDay = getPreviousTradingDay(targetDate);
        List<Candle> dailyCandles = getHistoricalData(instrumentToken, previousTradingDay, previousTradingDay, "day");
        if (dailyCandles != null && !dailyCandles.isEmpty()) {
            LoggingUtil.debug("PDH for " + instrumentToken + " on " + previousTradingDay + " is " + dailyCandles.get(0).getHigh());
            return dailyCandles.get(0).getHigh();
        }
        LoggingUtil.warning("Could not fetch PDH for " + instrumentToken + " for previous trading day of " + targetDate);
        return -1.0;
    }

    public double getPreviousDayClose(String instrumentToken, LocalDate targetDate) {
        LoggingUtil.info("Fetching Previous Day Close for " + instrumentToken + " relative to " + targetDate);
        LocalDate previousTradingDay = getPreviousTradingDay(targetDate);
        List<Candle> dailyCandles = getHistoricalData(instrumentToken, previousTradingDay, previousTradingDay, "day");
        if (dailyCandles != null && !dailyCandles.isEmpty()) {
            LoggingUtil.debug("PDC for " + instrumentToken + " on " + previousTradingDay + " is " + dailyCandles.get(0).getClose());
            return dailyCandles.get(0).getClose();
        }
        LoggingUtil.warning("Could not fetch PDC for " + instrumentToken + " for previous trading day of " + targetDate);
        return -1.0;
    }

    // --- WebSocket Methods ---
    public void connectWebSocket(ArrayList<Long> tokensToSubscribe) {
        if (this.accessToken == null || this.publicToken == null || this.apiKey == null) {
            LoggingUtil.error("Cannot connect WebSocket: Missing critical credentials (accessToken, publicToken, or apiKey).");
            if (onWebSocketErrorCallback != null) onWebSocketErrorCallback.accept("WebSocket connection failed: Missing credentials.");
            return;
        }

        if (kiteTicker != null && kiteTicker.isConnectionOpen()) {
            LoggingUtil.info("WebSocket already connected or connecting. Subscribing to additional tokens if any.");
            kiteTicker.subscribe(tokensToSubscribe); // Subscribe to new tokens
            kiteTicker.setMode(tokensToSubscribe, KiteTicker.modeFull); // Ensure mode is set for new tokens
            return;
        }

        LoggingUtil.info("Attempting to connect WebSocket for tokens: " + tokensToSubscribe);
        kiteTicker = new KiteTicker(this.accessToken, this.apiKey); // Public token often not needed for constructor, but for session.
                                                                     // Some SDK versions might use public_token from setAccessToken.
                                                                     // Let's assume the SDK handles this based on prior setAccessToken/setPublicToken on KiteConnect obj.
                                                                     // If specific constructor needed: new KiteTicker(this.accessToken, this.apiKey, this.publicToken);

        kiteTicker.setOnConnectedListener(() -> {
            LoggingUtil.info("WebSocket Connected.");
            if (onWebSocketConnectCallback != null) {
                onWebSocketConnectCallback.run();
            }
            if (tokensToSubscribe != null && !tokensToSubscribe.isEmpty()) {
                kiteTicker.subscribe(tokensToSubscribe);
                // Set mode for ticks. modeQuote for basic LTP, modeFull for LTP, depth, volume etc.
                kiteTicker.setMode(tokensToSubscribe, KiteTicker.modeFull);
                LoggingUtil.info("Subscribed to tokens: " + tokensToSubscribe + " with modeFull.");
            }
        });

        kiteTicker.setOnDisconnectedListener(() -> {
            LoggingUtil.warning("WebSocket Disconnected.");
            if (onWebSocketDisconnectCallback != null) {
                onWebSocketDisconnectCallback.run();
            }
            // TODO: Implement reconnection logic here or notify main application
        });

        kiteTicker.setOnErrorListener(e -> {
            LoggingUtil.error("WebSocket Error: " + e.getMessage(), e);
            if (onWebSocketErrorCallback != null) {
                onWebSocketErrorCallback.accept("WebSocket error: " + e.getMessage());
            }
            // Handle specific errors, e.g., token invalid, connection refused
        });

        kiteTicker.setOnTickerArrivalListener(ticks -> {
            // LoggingUtil.debug("Ticks arrived: " + ticks.size());
            transformAndPassKiteTicksToCallback(ticks);
        });

        // Optional: Order updates via WebSocket
        // kiteTicker.setOnOrderUpdateListener(order -> {
        //     LoggingUtil.info("Order Update via WebSocket: " + order.orderId + ", Status: " + order.status);
        //     // TODO: Propagate this to OrderManager or a dedicated callback
        // });

        kiteTicker.setTryReconnection(true);
        kiteTicker.setMaximumRetries(10); // Example
        kiteTicker.setMaximumRetryInterval(30); // Example: 30 seconds
        kiteTicker.connect();
    }

    public void disconnectWebSocket() {
        LoggingUtil.info("disconnectWebSocket called.");
        if (kiteTicker != null && kiteTicker.isConnectionOpen()) {
            kiteTicker.disconnect();
            LoggingUtil.info("WebSocket disconnect initiated.");
        } else {
            LoggingUtil.info("WebSocket not connected or already disconnected.");
        }
    }

    private void transformAndPassKiteTicksToCallback(ArrayList<Tick> kiteTicks) {
        if (onTickCallback == null) return;

        for (Tick kiteTick : kiteTicks) {
            ZonedDateTime tickTimestamp = ZonedDateTime.now(); // Default to arrival time
            if (kiteTick.getTickTimestamp() != null) { // Kite SDK provides this
                tickTimestamp = kiteTick.getTickTimestamp().toInstant().atZone(ZoneId.systemDefault());
            } else if (kiteTick.getLastTradeTime() != null) { // Fallback
                tickTimestamp = kiteTick.getLastTradeTime().toInstant().atZone(ZoneId.systemDefault());
            }

            String instrumentTokenStr = String.valueOf(kiteTick.getInstrumentToken());

            MarketDepth ourMarketDepth = null;
            if (kiteTick.getMarketDepth() != null && !kiteTick.getMarketDepth().isEmpty()){
                ourMarketDepth = transformKiteDepthToOurDepth(instrumentTokenStr, tickTimestamp, kiteTick.getMarketDepth());
            }

            TickData tickData = new TickData(
                    tickTimestamp,
                    instrumentTokenStr,
                    kiteTick.getLastTradedPrice(),
                    kiteTick.getLastTradedQuantity(),
                    kiteTick.getVolumeTradedToday(),
                    kiteTick.getAverageTradePrice(),
                    ourMarketDepth // Pass our transformed market depth
            );
            onTickCallback.accept(tickData);

            // If a separate, more detailed depth callback is needed for full order book updates (not just top 5 with tick)
            // and if `onDepthCallback` is set, one might call it here if `ourMarketDepth` is comprehensive enough
            // or if a different subscription mode provides deeper depth updates.
            // For modeFull, the depth in tick is usually the top 5.
            if (ourMarketDepth != null && onDepthCallback != null) {
                 // onDepthCallback.accept(ourMarketDepth); // If this callback expects MarketDepth object
            }
        }
    }

    private MarketDepth transformKiteDepthToOurDepth(String instrumentToken, ZonedDateTime timestamp, Map<String, ArrayList<Depth>> kiteDepthMap) {
        List<MarketDepth.DepthLevel> bids = new ArrayList<>();
        List<MarketDepth.DepthLevel> asks = new ArrayList<>();

        if (kiteDepthMap != null) {
            ArrayList<Depth> bidDepths = kiteDepthMap.getOrDefault("buy", new ArrayList<>());
            for (Depth d : bidDepths) {
                bids.add(new MarketDepth.DepthLevel(d.getPrice(), d.getQuantity(), d.getOrders()));
            }
            ArrayList<Depth> askDepths = kiteDepthMap.getOrDefault("sell", new ArrayList<>());
            for (Depth d : askDepths) {
                asks.add(new MarketDepth.DepthLevel(d.getPrice(), d.getQuantity(), d.getOrders()));
            }
        }
        return new MarketDepth(instrumentToken, timestamp, bids, asks);
    }


    // --- Callbacks for WebSocket events ---
    public void setOnTickCallback(Consumer<TickData> callback) { this.onTickCallback = callback; }
    public void setOnDepthCallback(Consumer<MarketDepth> callback) { this.onDepthCallback = callback; } // For dedicated depth updates
    public void setOnConnectCallback(Runnable callback) { this.onWebSocketConnectCallback = callback; }
    public void setOnDisconnectCallback(Runnable callback) { this.onWebSocketDisconnectCallback = callback; }
    public void setOnErrorCallback(Consumer<String> callback) { this.onWebSocketErrorCallback = callback; } // Renamed for clarity
    public void setOnKiteExceptionCallback(Consumer<KiteException> callback) { this.onKiteExceptionCallback = callback; }


    // --- Order Management Methods ---
    public String placeOrder(Map<String, Object> params, String variety) {
        LoggingUtil.info("Placing order with params: " + params + ", variety: " + variety);
        if (this.accessToken == null) {
            LoggingUtil.error("Access token is null. Cannot place order.");
            return null;
        }
        try {
            OrderParams orderParams = new OrderParams();
            orderParams.exchange = (String) params.get("exchange");
            orderParams.tradingsymbol = (String) params.get("tradingsymbol"); // This must be the trading symbol (e.g. "INFY")
            orderParams.transactionType = (String) params.get("transaction_type");
            orderParams.quantity = (Integer) params.get("quantity");
            orderParams.product = (String) params.get("product");
            orderParams.orderType = (String) params.get("order_type");

            if (params.containsKey("price")) orderParams.price = (Double) params.get("price");
            if (params.containsKey("trigger_price")) orderParams.triggerPrice = (Double) params.get("trigger_price");
            if (params.containsKey("validity")) orderParams.validity = (String) params.get("validity");
            if (params.containsKey("tag")) orderParams.tag = (String) params.get("tag");
            // Add other optional params as needed

            Order order = kiteConnect.placeOrder(orderParams, variety);
            LoggingUtil.info("Order placed successfully. Order ID: " + order.orderId + " for " + orderParams.tradingsymbol);
            return order.orderId;
        } catch (KiteException e) {
            LoggingUtil.error("KiteException placing order for " + params.get("tradingsymbol") + ": " + e.getMessage() + " Code: " + e.code, e);
            if (onKiteExceptionCallback != null) onKiteExceptionCallback.accept(e);
        } catch (ClassCastException cce) {
            LoggingUtil.error("ClassCastException parsing order params. Check data types. Params: " + params, cce);
        }
        catch (Exception e) {
            LoggingUtil.error("Unexpected error placing order for " + params.get("tradingsymbol") + ": " + e.getMessage(), e);
        }
        return null;
    }

    public String modifyOrder(String orderId, Map<String, Object> params, String variety) {
        LoggingUtil.warning("modifyOrder is not fully implemented yet in KiteService.");
        // TODO: Implement actual Kite Connect SDK call
        return "simulated_modified_order_id_" + orderId;
    }

    public String cancelOrder(String orderId, String variety) {
        LoggingUtil.warning("cancelOrder is not fully implemented yet in KiteService.");
        // TODO: Implement actual Kite Connect SDK call
        return "simulated_cancelled_order_id_" + orderId;
    }

    public static void main(String[] args) {
        // Load credentials from environment or config for testing
        String apiKey = System.getenv("KITE_API_KEY_TEST");
        String userId = System.getenv("KITE_USER_ID_TEST");
        String apiSecret = System.getenv("KITE_API_SECRET_TEST");
        String requestTokenTest = System.getenv("KITE_REQUEST_TOKEN_TEST");
        String accessTokenTest = System.getenv("KITE_ACCESS_TOKEN_TEST");
        String publicTokenTest = System.getenv("KITE_PUBLIC_TOKEN_TEST");

        if (apiKey == null || userId == null) {
            LoggingUtil.error("KITE_API_KEY_TEST and KITE_USER_ID_TEST environment variables must be set.");
            return;
        }

        KiteService kiteService = new KiteService(apiKey, userId);
        kiteService.setOnKiteExceptionCallback(e ->
            LoggingUtil.error("MainTest-KiteException: " + e.getMessage() + " Code: " + e.code)
        );

        if (accessTokenTest != null && publicTokenTest != null) {
            LoggingUtil.info("Using existing access/public tokens for testing.");
            kiteService.setTokens(accessTokenTest, publicTokenTest);
        } else if (requestTokenTest != null && apiSecret != null) {
            LoggingUtil.info("Attempting to generate session with request token...");
            kiteService.generateSession(requestTokenTest, apiSecret);
            if (kiteService.getAccessToken() != null) {
                 LoggingUtil.info("Session generated. New Access Token: " + kiteService.getAccessToken() + ", New Public Token: " + kiteService.getPublicToken());
                 LoggingUtil.info("Please save these tokens as environment variables KITE_ACCESS_TOKEN_TEST and KITE_PUBLIC_TOKEN_TEST for future runs.");
            } else {
                LoggingUtil.error("Failed to generate session even with request token and API secret.");
                return;
            }
        } else {
            LoggingUtil.error("Need either (KITE_ACCESS_TOKEN_TEST & KITE_PUBLIC_TOKEN_TEST) or (KITE_REQUEST_TOKEN_TEST & KITE_API_SECRET_TEST) to be set.");
            LoggingUtil.info("To get a KITE_REQUEST_TOKEN_TEST, visit: " + kiteService.kiteConnect.getLoginURL() + " and complete login.");
            return;
        }

        if(kiteService.getAccessToken() == null) {
            LoggingUtil.error("Failed to obtain access token. Exiting test.");
            return;
        }

        String testInstrumentToken = "256265"; // INFY NSE (numerical token)
        LoggingUtil.info("Fetching historical data for " + testInstrumentToken);
        List<Candle> candles = kiteService.getHistoricalData(testInstrumentToken,
                LocalDate.now().minusDays(20), // Fetch more data to test pagination/limits if any
                LocalDate.now().minusDays(1),
                "minute"); // minute interval
        LoggingUtil.info("Fetched " + candles.size() + " minute candles for " + testInstrumentToken);
        if (!candles.isEmpty()) {
            LoggingUtil.info("First fetched candle: " + candles.get(0));
            LoggingUtil.info("Last fetched candle: " + candles.get(candles.size() - 1));
        } else {
            LoggingUtil.warning("No historical data fetched. Check API limits, token validity, or date range.");
        }

        double pdc = kiteService.getPreviousDayClose(testInstrumentToken, LocalDate.now());
        LoggingUtil.info("PDC for " + testInstrumentToken + " (relative to today): " + pdc);

        // Test order placement (use with extreme caution)
        // Map<String, Object> orderParams = new HashMap<>();
        // orderParams.put("tradingsymbol", "INFY");
        // orderParams.put("exchange", "NSE");
        // orderParams.put("transaction_type", "BUY");
        // orderParams.put("quantity", 1);
        // orderParams.put("product", "CNC");
        // orderParams.put("order_type", "MARKET"); // Market order for testing simplicity if sure
        // // orderParams.put("price", 1000.00); // For LIMIT order
        // orderParams.put("tag", "KiteServiceMainTest");
        // String orderId = kiteService.placeOrder(orderParams, "regular");
        // if (orderId != null) {
        //     LoggingUtil.info("Test order placed. Order ID: " + orderId);
        // } else {
        //     LoggingUtil.error("Test order placement failed.");
        // }
    }
}
