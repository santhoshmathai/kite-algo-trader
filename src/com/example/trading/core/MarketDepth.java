package com.example.trading.core;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents the market depth (order book) for a specific instrument at a point in time.
 * It contains lists of buy (bid) and sell (ask) orders, each with price, quantity, and number of orders.
 * Zerodha provides depth up to 5 levels.
 */
public class MarketDepth {

    /**
     * Represents a single level in the market depth (either a bid or an ask).
     */
    public static class DepthLevel {
        private final double price;
        private final int quantity;
        private final int orders;

        public DepthLevel(double price, int quantity, int orders) {
            this.price = price;
            this.quantity = quantity;
            this.orders = orders;
        }

        public double getPrice() {
            return price;
        }

        public int getQuantity() {
            return quantity;
        }

        public int getOrders() {
            return orders;
        }

        @Override
        public String toString() {
            return "DepthLevel{" +
                   "price=" + price +
                   ", quantity=" + quantity +
                   ", orders=" + orders +
                   '}';
        }
    }

    private final String instrumentToken;
    private final ZonedDateTime timestamp; // Timestamp when this market depth was captured
    private final List<DepthLevel> bids;  // Buy orders, typically sorted highest price first
    private final List<DepthLevel> asks;  // Sell orders, typically sorted lowest price first

    /**
     * Constructor for MarketDepth.
     *
     * @param instrumentToken The instrument token.
     * @param timestamp The time of this market depth snapshot.
     * @param bids A list of bid levels. Expected to be sorted by price descending.
     * @param asks A list of ask levels. Expected to be sorted by price ascending.
     */
    public MarketDepth(String instrumentToken, ZonedDateTime timestamp, List<DepthLevel> bids, List<DepthLevel> asks) {
        if (instrumentToken == null || instrumentToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Instrument token cannot be null or empty.");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp cannot be null.");
        }
        this.instrumentToken = instrumentToken;
        this.timestamp = timestamp;
        // Store sorted copies to ensure order
        this.bids = bids != null ? bids.stream()
                                      .sorted(Comparator.comparingDouble(DepthLevel::getPrice).reversed())
                                      .collect(Collectors.toList())
                                : List.of();
        this.asks = asks != null ? asks.stream()
                                      .sorted(Comparator.comparingDouble(DepthLevel::getPrice))
                                      .collect(Collectors.toList())
                                : List.of();
    }

    public String getInstrumentToken() {
        return instrumentToken;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public List<DepthLevel> getBids() {
        return bids; // Returns an unmodifiable list if original was, or a new list from stream
    }

    public List<DepthLevel> getAsks() {
        return asks; // Returns an unmodifiable list if original was, or a new list from stream
    }

    public double getBestBidPrice() {
        return bids.isEmpty() ? 0.0 : bids.get(0).getPrice();
    }

    public int getBestBidQuantity() {
        return bids.isEmpty() ? 0 : bids.get(0).getQuantity();
    }

    public double getBestAskPrice() {
        return asks.isEmpty() ? 0.0 : asks.get(0).getPrice();
    }

    public int getBestAskQuantity() {
        return asks.isEmpty() ? 0 : asks.get(0).getQuantity();
    }

    @Override
    public String toString() {
        return "MarketDepth{" +
               "instrumentToken='" + instrumentToken + '\'' +
               ", timestamp=" + timestamp +
               ", bids=" + (bids != null ? Arrays.toString(bids.toArray()) : "[]") +
               ", asks=" + (asks != null ? Arrays.toString(asks.toArray()) : "[]") +
               '}';
    }
}
