package com.example.trading;

import com.example.trading.core.TickData;
import com.example.trading.data.TimeSeriesManager;
import com.example.trading.indicators.IndicatorCalculator;
import com.example.trading.order.OrderManager;
import com.example.trading.risk.RiskManager;
import com.example.trading.strategy.ORBStrategy;
import com.example.trading.util.Config; // Import Config
import com.example.trading.util.LoggingUtil; // Import LoggingUtil

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
    private static Object kiteService;
    private static TimeSeriesManager timeSeriesManager;
    private static IndicatorCalculator indicatorCalculator; // May not need an instance if all methods are static
    private static OrderManager orderManager;
    private static RiskManager riskManager;
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
        kiteService = new Object(); // Mock KiteService

        timeSeriesManager = new TimeSeriesManager();
        orderManager = new OrderManager(kiteService);
        riskManager = new RiskManager(orderManager);
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
            double pdc = 100.0; // Mock PDC

            // TODO: Load strategy-specific parameters from config (e.g., ORB range, times, quantity)
            // For simplicity, using hardcoded defaults here.
            int orbOpeningRangeMinutes = 15; // As per requirement
            LocalTime marketOpenTime = LocalTime.of(9, 15);
            LocalTime strategyEndTime = LocalTime.of(15, 00); // When to stop initiating new ORB trades

            ORBStrategy strategy = new ORBStrategy(
                    timeSeriesManager,
                    orderManager,
                    riskManager,
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
        // Mock implementation for testing
    }

    // Removed mapNumericalTokenToSymbol as strategy now holds numerical token.

    private static synchronized void shutdown() {
        if (!isRunning) {
            return;
        }
        LoggingUtil.info("Initiating shutdown sequence...");
        isRunning = false;

        LoggingUtil.info("System shutdown actions complete. Exiting.");
    }
}
