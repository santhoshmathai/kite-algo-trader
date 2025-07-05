package com.example.trading;

import com.example.trading.core.TickData;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.indicators.IndicatorCalculator;
import com.example.trading.kite.KiteService;
import com.example.trading.order.OrderManager;
import com.example.trading.strategy.ORBStrategy;
import com.example.trading.util.Config; // Import Config
import com.example.trading.util.LoggingUtil; // Import LoggingUtil

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Main class for the Intraday Trading System.
 * This class will initialize and coordinate all other components.
 */
public class TradingSystemMain {

    // Declare components
    private static Config config; // Config instance
    // private static LoggingUtil logger; // LoggingUtil methods are static
    private static KiteService kiteService;
    private static TimeSeriesManager timeSeriesManager;
    private static IndicatorCalculator indicatorCalculator; // May not need an instance if all methods are static
    private static OrderManager orderManager;
    private static List<ORBStrategy> strategies; // Example: List of strategies
    private static volatile boolean isRunning = true;


    public static void main(String[] args) {
        // 1. Initialize Configuration and Logging
        config = new Config(); // Loads from config.properties
        LoggingUtil.setLevel(config.getLogLevel()); // Set log level from config
        LoggingUtil.info("Intraday Trading System Starting...");
        LoggingUtil.info("TradingSystemMain: Initializing components...");

        // --- Load Core Credentials from Config ---
        String apiKey = config.getApiKey();
        String userId = config.getUserId();
        String apiSecret = config.getApiSecret(); // Needed for generating session
        String existingAccessToken = config.getAccessToken();

        Scanner scanner = new Scanner(System.in); // For interactive input if credentials missing

        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
            LoggingUtil.warning("API Key not found in config.properties or is default.");
            System.out.print("Enter Kite API Key: ");
            apiKey = scanner.nextLine();
        }
        if (userId == null || userId.trim().isEmpty() || userId.equals("YOUR_USER_ID_HERE")) {
            LoggingUtil.warning("User ID not found in config.properties or is default.");
            System.out.print("Enter Kite User ID: ");
            userId = scanner.nextLine();
        }

        // 2. Initialize Core Services
        kiteService = new KiteService(apiKey, userId);

        // Session Management:
        // Prefer existing access token from config. If not valid/present, try to generate a new one.
        if (existingAccessToken != null && !existingAccessToken.trim().isEmpty() && !existingAccessToken.equals("YOUR_ACCESS_TOKEN_HERE_IF_ALREADY_GENERATED")) {
            LoggingUtil.info("Using existing Access Token from config.");
            kiteService.setAccessToken(existingAccessToken);
            // TODO: Add a check here to verify if the accessToken is still valid.
            // If not valid, then proceed to generate a new one.
        } else {
            LoggingUtil.info("Existing Access Token not found or is default in config. Attempting to generate a new session.");
            System.out.print("Enter Kite Request Token (obtained after manual login for generating a new session): ");
            String requestToken = scanner.nextLine();
            if (requestToken != null && !requestToken.trim().isEmpty()) {
                if (apiSecret == null || apiSecret.trim().isEmpty() || apiSecret.equals("YOUR_API_SECRET_HERE")) {
                    LoggingUtil.warning("API Secret not found in config.properties or is default. Required for session generation.");
                    System.out.print("Enter Kite API Secret: ");
                    apiSecret = scanner.nextLine();
                }
                // Actual KiteConnect SDK's generateSession needs apiSecret.
                // The KiteService skeleton's generateSession needs to be updated to accept it.
                // For now, we assume KiteService.generateSession will handle it (or simulate).
                // kiteService.generateSession(requestToken, apiSecret); // Ideal call
                kiteService.generateSession(requestToken); // Current skeleton call
                LoggingUtil.info("Attempted to generate session. Check KiteService logs for status.");
                // After successful session generation, the new access token should be saved back to config
                // String newAccessToken = kiteService.getAccessToken(); // Assuming KiteService has such a getter after session generation
                // if(newAccessToken != null) config.setAccessToken(newAccessToken); // Persist it
            } else {
                LoggingUtil.error("No valid existing access token and no new request token provided. KiteService may not function correctly.");
            }
        }

        timeSeriesManager = new TimeSeriesManager();
        orderManager = new OrderManager(kiteService);
        indicatorCalculator = new IndicatorCalculator();

        // 3. Initialize Strategies based on Config
        strategies = new ArrayList<>();
        List<String> instrumentSymbolsFromConfig = config.getStrategyInstruments();
        LoggingUtil.info("Instruments from config: " + instrumentSymbolsFromConfig);

        for (String instrumentSymbol : instrumentSymbolsFromConfig) {
            // This is a placeholder for mapping trading symbol to WebSocket numerical token (instrument_token for Kite).
            // In a real system, you'd fetch this mapping from Kite's instrument list or have it configured.
            String numericalTokenStr; // Kite's instrument token, usually numerical, as a String
            if (instrumentSymbol.equalsIgnoreCase("NIFTYBANK")) {
                numericalTokenStr = "260105"; // Nifty Bank Index
            } else if (instrumentSymbol.equalsIgnoreCase("RELIANCE")) {
                numericalTokenStr = "738561"; // Reliance Industries
            } else {
                LoggingUtil.warning("No hardcoded numerical (WebSocket) token for instrument symbol: " + instrumentSymbol + ". Skipping strategy setup for it.");
                continue;
            }

            // Fetch Previous Day Close (PDC) for gap analysis
            double pdc = kiteService.getPreviousDayClose(numericalTokenStr, LocalDate.now());
            if (pdc <= 0) {
                LoggingUtil.warning("Could not fetch PDC for " + instrumentSymbol + " (token: " + numericalTokenStr + "). ORB strategy might not work correctly for it today.");
                // Optionally, skip strategy setup if PDC is crucial and not available
                // continue;
            }

            // TODO: Load strategy-specific parameters from config (e.g., ORB range, times, quantity)
            // For simplicity, using hardcoded defaults here.
            int orbOpeningRangeMinutes = 15; // As per requirement
            LocalTime marketOpenTime = LocalTime.of(9, 15);
            LocalTime strategyEndTime = LocalTime.of(15, 00); // When to stop initiating new ORB trades

            ORBStrategy strategy = new ORBStrategy(
                    timeSeriesManager,
                    orderManager,
                    instrumentSymbol,      // The trading symbol (e.g., "NIFTYBANK") used for orders
                    numericalTokenStr,     // The numerical instrument token (as String) used for data fetching & WebSocket
                    pdc,                   // Previous Day's Close
                    orbOpeningRangeMinutes,
                    marketOpenTime,
                    strategyEndTime
            );
            strategies.add(strategy);
            LoggingUtil.info("ORB Strategy for " + instrumentSymbol + " (PDC: " + pdc + ", WebSocket Token: " + numericalTokenStr + ") initialized.");
        }

        // 4. Setup Callbacks for WebSocket and Start Connection
        setupKiteServiceCallbacks();

        // Instruments to subscribe to via WebSocket. Derived from configured strategies.
        ArrayList<Long> tokensToSubscribe = new ArrayList<>();
        // Instruments to subscribe to via WebSocket. Derived from configured strategies.
        ArrayList<Long> tokensToSubscribe = strategies.stream()
                .map(s -> {
                    try {
                        return Long.parseLong(s.getNumericalInstrumentToken()); // Assuming ORBStrategy has a getter for this
                    } catch (NumberFormatException e) {
                        LoggingUtil.error("Invalid numerical token format for strategy: " + s.getInstrumentSymbol() + " - " + s.getNumericalInstrumentToken(), e);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        LoggingUtil.info("Attempting to connect WebSocket for numerical tokens: " + tokensToSubscribe);
        if (!tokensToSubscribe.isEmpty()) {
            kiteService.connectWebSocket(tokensToSubscribe); // Initiates connection
        } else {
            LoggingUtil.warning("No valid numerical tokens for WebSocket subscription derived from strategies. WebSocket will not connect.");
        }

        // 5. Main Application Loop & Shutdown Handling
        LoggingUtil.info("System is running. Waiting for market data and events...");
        LoggingUtil.info("Type 'exit' and press Enter in the console to shutdown gracefully.");


        // Start a separate thread to listen for console input for shutdown
        Thread consoleListenerThread = new Thread(() -> {
            Scanner consoleScannerForExit = new Scanner(System.in);
            while (isRunning) {
                if (consoleScannerForExit.hasNextLine()) {
                    String input = consoleScannerForExit.nextLine();
                    if ("exit".equalsIgnoreCase(input.trim())) {
                        LoggingUtil.info("'exit' command received.");
                        shutdown();
                        break;
                    }
                }
            }
            consoleScannerForExit.close();
        });
        consoleListenerThread.setName("ConsoleShutdownListener");
        consoleListenerThread.start();

        // Register a shutdown hook for graceful termination on Ctrl+C or OS signal
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LoggingUtil.info("JVM Shutdown hook activated.");
            shutdown();
        }));

        // Keep the main thread alive while isRunning is true
        while (isRunning) {
            try {
                Thread.sleep(1000); // Keep main thread alive
            } catch (InterruptedException e) {
                LoggingUtil.info("Main application thread interrupted.");
                Thread.currentThread().interrupt();
                shutdown();
            }
        }
        LoggingUtil.info("Exiting main method.");
        scanner.close(); // Close the initial scanner if it's still open and not System.in itself.
    }

    private static void setupKiteServiceCallbacks() {
        kiteService.setOnTickCallback((TickData tick) -> {
            timeSeriesManager.addTick(tick);
            for (ORBStrategy strategy : strategies) {
                // TickData.instrumentToken is the numerical token (as String).
                // ORBStrategy should be checked against this numerical token.
                if (strategy.getNumericalInstrumentToken().equals(tick.getInstrumentToken())) {
                     strategy.evaluate();
                }
            }
        });

        kiteService.setOnConnectCallback(() -> {
            LoggingUtil.info("Kite WebSocket connected successfully!");
        });

        kiteService.setOnDisconnectCallback(() -> {
            LoggingUtil.warning("Kite WebSocket disconnected.");
            // TODO: Implement robust reconnection strategy.
        });

        kiteService.setOnErrorCallback((String errorMsg) -> {
            LoggingUtil.error("Kite WebSocket error: " + errorMsg);
        });

        // TODO: Setup callback for order updates from KiteService to OrderManager
    }

    // Removed mapNumericalTokenToSymbol as strategy now holds numerical token.

    private static synchronized void shutdown() {
        if (!isRunning) {
            return;
        }
        LoggingUtil.info("Initiating shutdown sequence...");
        isRunning = false;

        if (kiteService != null) {
            kiteService.disconnectWebSocket();
        }
        LoggingUtil.info("System shutdown actions complete. Exiting.");
    }
}
