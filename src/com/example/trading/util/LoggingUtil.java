package com.example.trading.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Utility class for logging application events.
 * Provides a simple, configurable logger.
 * Uses java.util.logging (JUL).
 */
public class LoggingUtil {

    private static Logger logger;
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Static block to configure the root logger or a specific logger
    // This configuration applies globally if not overridden.
    static {
        logger = Logger.getLogger("TradingSystemLogger"); // Create a custom logger
        logger.setUseParentHandlers(false); // Don't use the root logger's handlers (e.g., default console)

        // Remove existing handlers to prevent duplicate logging if this is re-initialized
        Handler[] handlers = logger.getHandlers();
        for (Handler handler : handlers) {
            logger.removeHandler(handler);
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new SimpleFormatter());

        // Set default level. Can be overridden by config.
        Level defaultLevel = Level.INFO;
        String configuredLevel = System.getProperty("trading.log.level", System.getenv("LOG_LEVEL"));
        if (configuredLevel != null) {
            try {
                defaultLevel = Level.parse(configuredLevel.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("LoggingUtil: Invalid log level configured '" + configuredLevel + "'. Defaulting to INFO.");
            }
        }

        logger.setLevel(defaultLevel);
        consoleHandler.setLevel(defaultLevel); // Handler level also needs to be set

        logger.addHandler(consoleHandler);
    }

    /**
     * Custom log formatter.
     */
    private static class SimpleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return String.format("[%s] [%s] [%s] %s: %s%n",
                    dtf.format(LocalDateTime.now()), // Using current time for log entry, not record.getMillis() for consistency if logs are delayed
                    record.getLevel().getName(),
                    Thread.currentThread().getName(), // Log the thread name
                    getSimpleClassName(record.getLoggerName()), // Or record.getSourceClassName() if available and set
                    formatMessage(record));
        }

        private String getSimpleClassName(String loggerName) {
            // If using source class name, it might be more direct.
            // For logger name, it's what was passed to Logger.getLogger().
            // This is a basic attempt to simplify.
            if (loggerName == null) return "DefaultLogger";
            int lastDot = loggerName.lastIndexOf(".");
            if (lastDot > 0 && lastDot < loggerName.length() - 1) {
                return loggerName.substring(lastDot + 1);
            }
            return loggerName;
        }
    }

    /**
     * Sets the global logging level.
     * @param levelName The name of the level (e.g., "INFO", "WARNING", "FINE").
     */
    public static void setLevel(String levelName) {
        try {
            Level newLevel = Level.parse(levelName.toUpperCase());
            logger.setLevel(newLevel);
            for (Handler handler : logger.getHandlers()) {
                handler.setLevel(newLevel); // Important: set level on handlers too
            }
            info("Log level set to: " + newLevel.getName());
        } catch (IllegalArgumentException e) {
            warning("Invalid log level specified: " + levelName);
        }
    }

    // --- Logging methods ---

    public static void info(String message) {
        logger.log(Level.INFO, message);
    }

    public static void info(String message, Object... params) {
        logger.log(Level.INFO, String.format(message, params));
    }

    public static void warning(String message) {
        logger.log(Level.WARNING, message);
    }

    public static void warning(String message, Object... params) {
        logger.log(Level.WARNING, String.format(message, params));
    }

    public static void error(String message) {
        logger.log(Level.SEVERE, message);
    }

    public static void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }

    public static void error(String message, Object... params) {
        logger.log(Level.SEVERE, String.format(message, params));
    }

    public static void debug(String message) { // Corresponds to FINE in JUL
        logger.log(Level.FINE, message);
    }

    public static void debug(String message, Object... params) { // Corresponds to FINE in JUL
        logger.log(Level.FINE, String.format(message, params));
    }

    public static void trace(String message) { // Corresponds to FINER or FINEST in JUL
        logger.log(Level.FINER, message);
    }

    public static void trace(String message, Object... params) { // Corresponds to FINER or FINEST in JUL
        logger.log(Level.FINER, String.format(message, params));
    }


    // Example usage
    public static void main(String[] args) {
        // Config could set this level
        // LoggingUtil.setLevel("FINE"); // To see debug messages

        LoggingUtil.info("This is an informational message from %s.", "MainTest");
        LoggingUtil.warning("This is a warning message.");
        LoggingUtil.error("This is an error message without exception.");
        LoggingUtil.error("This is an error with an exception.", new RuntimeException("Test Exception"));

        LoggingUtil.debug("This is a debug message (JUL FINE)."); // Will only show if level is FINE or lower
        LoggingUtil.trace("This is a trace message (JUL FINER)."); // Will only show if level is FINER or lower

        // Test with a different logger name context (though our static logger is global)
        Logger anotherLogger = Logger.getLogger("com.example.another.AnotherComponent");
        anotherLogger.info("Message from another component's logger context.");
        // Note: Our static methods use the "TradingSystemLogger".
        // If individual components need their own logger instances with class context,
        // they should do Logger.getLogger(MyClass.class.getName()) and configure it or use parent's.
    }
}
