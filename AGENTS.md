# Agent Instructions for Intraday Trading System (Zerodha)

This document provides guidelines for AI agents working on this Java-based intraday trading system.

## Core Requirements:

1.  **Language:** All code must be written in Java.
2.  **Zerodha Kite Connect SDK:**
    *   The system integrates with Zerodha Kite Connect for market data (WebSocket for ticks and depth) and trading operations (REST API for historical data, order placement, etc.).
    *   Assume the Kite Connect SDK JAR files are available or will be placed in a `lib` directory. All interactions with Zerodha's API must go through this SDK.
    *   No other external dependencies should be introduced unless explicitly approved.
3.  **Modular Object-Oriented Design:**
    *   Adhere to a clean, logically separated, object-oriented design.
    *   Key components (e.g., `Candle`, `TickData`, `MarketDepth`, `KiteService`, `TimeSeriesManager`, `ORBStrategy`, `OrderManager`) should be in their own classes and appropriate packages.
4.  **Inline Comments:** Provide clear and concise inline comments explaining the purpose of classes, methods, and complex logic blocks.
5.  **Error Handling:** Implement robust error handling, especially for API interactions and data processing.
6.  **Configuration:** Externalize configurable parameters (API keys, instrument lists, strategy settings) into a configuration mechanism (e.g., a `Config.java` file or properties file).
7.  **Logging:** Implement basic logging for important events, errors, and trading actions.

## Project Structure:

The project follows a standard Java structure:

```
intraday-trading-system-zerodha/
├── src/
│   └── com/
│       └── example/
│           └── trading/
│               ├── core/         // Candle, TickData, MarketDepth
│               ├── data/         // TimeSeriesManager
│               ├── indicators/   // IndicatorCalculator
│               ├── kite/         // KiteService (Kite Connect SDK interaction)
│               ├── strategy/     // Trading strategies (e.g., ORBStrategy)
│               ├── order/        // OrderManager
│               ├── util/         // Config, LoggingUtil
│               └── TradingSystemMain.java // Main application class
└── lib/                          // For Zerodha SDK JARs
└── AGENTS.md
```

## Development Process:

1.  **Understand the Plan:** Before coding, ensure you understand the current development plan and the specific task at hand.
2.  **Incremental Implementation:** Implement features incrementally, focusing on one component or functionality at a time.
3.  **Testing (Conceptual):** While formal unit tests might be added later, think about how each component would be tested. For instance, `KiteService` would need mock data or a live (sandboxed) connection for testing.
4.  **Clarity and Readability:** Write code that is easy to understand and maintain.

## Specific Instructions:

*   When implementing `KiteService`, ensure that methods are clearly defined for:
    *   Authentication and session management.
    *   Fetching historical data.
    *   Fetching instrument details (including previous day high/low).
    *   WebSocket connection for live ticks and market depth.
    *   Order placement, modification, and cancellation.
*   `TimeSeriesManager` should handle aggregation of ticks into candles for various timeframes (1-min, 5-min, 15-min, etc.) efficiently.
*   Strategies should be self-contained and use services like `TimeSeriesManager` for data and `OrderManager` for execution.

By following these guidelines, we aim to build a robust and maintainable trading system.Now, I will proceed to create the basic directory structure and empty Java files as placeholders. I'll start with the main application class and then the package structure.
