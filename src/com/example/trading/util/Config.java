package com.example.trading.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Handles application configuration.
 * Loads settings from a properties file (e.g., config.properties).
 * Provides access to configuration values like API keys, instrument lists, strategy parameters.
 */
public class Config {

    private Properties properties;
    private static final String CONFIG_FILE_NAME = "config.properties"; // Expected in the classpath or specific path

    // Default values or keys used in the properties file
    public static final String KITE_API_KEY = "kite.api.key";
    public static final String KITE_USER_ID = "kite.user.id";
    public static final String KITE_API_SECRET = "kite.api.secret"; // Needed for session generation
    public static final String KITE_ACCESS_TOKEN = "kite.access.token"; // Stored after session generation
    public static final String LOG_LEVEL = "log.level";
    public static final String STRATEGY_INSTRUMENTS = "strategy.instruments"; // Comma-separated list of instrument tokens/symbols

    public Config() {
        this("src/main/resources/" + CONFIG_FILE_NAME); // Default path, adjust if needed
                                                        // Or load from classpath: getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)
    }

    public Config(String propertiesFilePath) {
        properties = new Properties();
        try (InputStream input = new FileInputStream(propertiesFilePath)) {
            if (input == null) {
                System.err.println("Config: Unable to find " + propertiesFilePath + ". Using defaults or environment variables where possible.");
                // Optionally, create a default properties file here if it doesn't exist, or rely on env vars / manual input.
                loadDefaultsOrFromEnv();
                return;
            }
            properties.load(input);
            System.out.println("Config: Successfully loaded configuration from " + propertiesFilePath);
        } catch (IOException ex) {
            System.err.println("Config: IOException while loading " + propertiesFilePath + ". Using defaults or environment variables. Error: " + ex.getMessage());
            loadDefaultsOrFromEnv();
        }
    }

    private void loadDefaultsOrFromEnv() {
        // Fallback to environment variables if properties file is not found or keys are missing
        // This makes it flexible for deployment (e.g. Docker)
        setIfNotSet(KITE_API_KEY, System.getenv("KITE_API_KEY"));
        setIfNotSet(KITE_USER_ID, System.getenv("KITE_USER_ID"));
        setIfNotSet(KITE_API_SECRET, System.getenv("KITE_API_SECRET"));
        setIfNotSet(KITE_ACCESS_TOKEN, System.getenv("KITE_ACCESS_TOKEN"));
        setIfNotSet(LOG_LEVEL, System.getenv("LOG_LEVEL"), "INFO"); // Default log level
        setIfNotSet(STRATEGY_INSTRUMENTS, System.getenv("STRATEGY_INSTRUMENTS"), ""); // Default empty list
    }

    private void setIfNotSet(String key, String value) {
        setIfNotSet(key, value, null);
    }

    private void setIfNotSet(String key, String value, String defaultValue) {
        if (properties.getProperty(key) == null) { // If not already set from file
            if (value != null && !value.trim().isEmpty()) {
                properties.setProperty(key, value);
            } else if (defaultValue != null) {
                properties.setProperty(key, defaultValue);
            }
        }
    }


    public String getApiKey() {
        return properties.getProperty(KITE_API_KEY);
    }

    public String getUserId() {
        return properties.getProperty(KITE_USER_ID);
    }

    public String getApiSecret() {
        return properties.getProperty(KITE_API_SECRET);
    }

    public String getAccessToken() {
        return properties.getProperty(KITE_ACCESS_TOKEN);
    }

    public void setAccessToken(String accessToken) {
        // TODO: Optionally save this back to the properties file, though this can be risky if not handled securely.
        // For now, just sets it in the current properties object.
        properties.setProperty(KITE_ACCESS_TOKEN, accessToken);
        System.out.println("Config: Access token updated in current session's configuration.");
        // Example: saveProperties(); // If implementing save
    }


    public String getLogLevel() {
        return properties.getProperty(LOG_LEVEL, "INFO"); // Default to INFO
    }

    public List<String> getStrategyInstruments() {
        String instrumentsStr = properties.getProperty(STRATEGY_INSTRUMENTS);
        if (instrumentsStr == null || instrumentsStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(instrumentsStr.split("\\s*,\\s*")); // Split by comma, trimming whitespace
    }

    // Generic getter for other properties
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    // TODO: Add a method to save properties if needed, e.g., to store a new access token.
    // public void saveProperties() {
    //     try (OutputStream output = new FileOutputStream("config.properties")) {
    //         properties.store(output, null);
    //     } catch (IOException io) {
    //         io.printStackTrace();
    //     }
    // }

    // Example of how to create a dummy config.properties file if it doesn't exist:
    // This should be placed in src/main/resources/config.properties
    /*
    # Sample config.properties
    # kite.api.key=YOUR_API_KEY
    # kite.user.id=YOUR_USER_ID
    # kite.api.secret=YOUR_API_SECRET
    # kite.access.token=
    # log.level=INFO
    # strategy.instruments=NIFTYBANK,INFY_EQ_TOKEN_HERE
    */
}
